package com.example.ottomatic.engine.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.example.ottomatic.R
import com.example.ottomatic.ServiceLocator
import com.example.ottomatic.data.BootFailureStore
import com.example.ottomatic.data.WorkflowRepository
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.WorkflowRunner
import com.example.ottomatic.engine.trigger.TriggerHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The long-lived owner of the workflow engine.
 *
 * [com.example.ottomatic.engine.WorkflowRunner.run] collects each trigger's
 * event [Flow] on the [CoroutineScope] it is handed. Previously the only scope
 * was the editor's `viewModelScope`, which is cancelled on Activity destruction
 * — so trigger subscriptions died the moment the user backgrounded the app, and
 * manifest receivers' [com.example.ottomatic.core.trigger.TriggerBus] emits were
 * silently dropped (`replay = 0`, no collector). This service owns a
 * [SupervisorJob]-backed scope that survives UI destruction, keeping the trigger
 * flows collected so background events actually reach the executor.
 *
 * Run as a foreground service (with a persistent, low-priority notification) so
 * the process is not reaped while macros are armed. Toggled per-workflow via the
 * [EXTRA_WORKFLOW_ID] / action intents; [ACTION_REARM_ALL] reloads persisted
 * enabled state (used on cold start and boot).
 *
 * NOTE on platform background-start restrictions (Android 12+): starting a
 * foreground service is only allowed while the app is in a foreground exemption
 * window. The UI toggle path (user taps Enable while the app is visible) is
 * always allowed. Reboot re-arming via BootReceiver relies on the
 * BOOT_COMPLETED temporary exemption and may be blocked on some OEMs; if so the
 * service starts on next user launch instead.
 */
@Suppress("TooManyFunctions") // Android Service lifecycle + arm/disarm/notification helpers.
class MacroEngineService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The armed runner per workflow id. Concurrent because [onCreate] reads
     * [Map.size] on the main thread while the arm/disarm coroutines mutate it.
     */
    private val activeJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /**
     * Serialises arm/disarm/rearm. Every one of them is a read-modify-write of
     * [activeJobs] spanning suspension points (joining the previous runner,
     * loading from disk), so without this two overlapping arms for the same id
     * both find no previous entry, both start a runner, and both store into the
     * map — orphaning one runner that keeps collecting its triggers against a
     * stale graph and can no longer be cancelled by anything, including a
     * disable/enable cycle.
     */
    private val armMutex = Mutex()

    private lateinit var host: TriggerHost
    private lateinit var executionContext: ExecutionContext
    private lateinit var repository: WorkflowRepository

    override fun onCreate() {
        super.onCreate()
        host = ServiceLocator.triggerHost
        executionContext = ServiceLocator.executionContext
        repository = ServiceLocator.workflowRepository
        // The service has started successfully; clear the boot-failure flag so
        // MainActivity doesn't show a stale battery-optimisation prompt for a
        // start that actually worked.
        BootFailureStore.clear(this)
        startForegroundCompat(buildNotification(activeJobs.size))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REARM_ALL -> scope.launch { armMutex.withLock { rearmAll() } }
            ACTION_ENABLE -> intent.getStringExtra(EXTRA_WORKFLOW_ID)?.let { id ->
                scope.launch {
                    repository.setEnabled(id, true)
                    armMutex.withLock { arm(id) }
                }
            }
            ACTION_DISABLE -> intent.getStringExtra(EXTRA_WORKFLOW_ID)?.let { id ->
                scope.launch {
                    repository.setEnabled(id, false)
                    armMutex.withLock { disarm(id) }
                }
            }
            // Re-read a macro that is *already* armed so a graph edit takes
            // effect without the user toggling it off and on. Deliberately does
            // not arm an unarmed macro: enabling is [ACTION_ENABLE]'s job, and a
            // RELOAD must never resurrect a macro the user just disabled.
            ACTION_RELOAD -> intent.getStringExtra(EXTRA_WORKFLOW_ID)?.let { id ->
                scope.launch {
                    // The armed check must be inside the lock with the arm it
                    // guards, or a concurrent disable can slip between them.
                    armMutex.withLock {
                        if (activeJobs.containsKey(id)) {
                            arm(id, announce = false)
                        } else if (activeJobs.isEmpty()) {
                            // startForegroundService spun us up for nothing.
                            stopSelf()
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Callers must hold [armMutex]. */
    private suspend fun rearmAll() {
        val enabled = repository.list().filter { it.enabled }
        if (enabled.isEmpty()) {
            if (activeJobs.isEmpty()) stopSelf()
            return
        }
        enabled.forEach { arm(it.id) }
    }

    /** Callers must hold [armMutex]. */
    private suspend fun arm(workflowId: String, announce: Boolean = true) {
        // Join, don't just cancel: each trigger's teardown runs in a `finally`
        // that releases a platform resource keyed by node id — the geofence
        // PendingIntent, the unique WorkManager name, the alarm PendingIntent.
        // An un-awaited cancel can therefore run *after* the new arm and tear
        // down what the new arm just registered.
        activeJobs.remove(workflowId)?.let { previous ->
            previous.cancel()
            previous.join()
        }
        val workflow = repository.load(workflowId) ?: return
        if (!workflow.enabled || workflow.id != workflowId) return
        val runner = WorkflowRunner(host, executionContext)
        activeJobs[workflowId] = runner.run(scope, workflow, announceEnabled = announce)
        refreshNotification()
    }

    /** Callers must hold [armMutex]. */
    private suspend fun disarm(workflowId: String) {
        // Join for the same reason [arm] does: the trigger teardown that
        // releases the geofence / alarm / unique work must have finished before
        // a subsequent arm of the same nodes re-registers them.
        activeJobs.remove(workflowId)?.let { previous ->
            previous.cancel()
            previous.join()
        }
        refreshNotification()
        if (activeJobs.isEmpty()) stopSelf()
    }

    private fun refreshNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(activeJobs.size))
    }

    private fun buildNotification(activeCount: Int): Notification {
        val text = if (activeCount == 0) "Standing by" else "$activeCount macro(s) armed"
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Ottomatic")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    @Suppress("CallApiLevelMismatch") // FOREGROUND_SERVICE_TYPE_SPECIAL_USE is API 34; guarded at runtime.
    private fun startForegroundCompat(notification: Notification) {
        ensureChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Ottomatic engine",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    companion object {
        const val ACTION_REARM_ALL = "com.example.ottomatic.action.REARM_ALL"
        const val ACTION_ENABLE = "com.example.ottomatic.action.ENABLE"
        const val ACTION_DISABLE = "com.example.ottomatic.action.DISABLE"
        const val ACTION_RELOAD = "com.example.ottomatic.action.RELOAD"
        const val EXTRA_WORKFLOW_ID = "workflowId"

        private const val NOTIFICATION_ID = 4242
        private const val CHANNEL_ID = "ottomatic.engine"

        /**
         * Starts the engine service for [action]. Uses [Context.startForegroundService]
         * (minSdk 26 = O, so always available). The service must call
         * startForeground within its ~5s window, which it does in [onCreate].
         */
        fun start(context: Context, action: String, workflowId: String? = null) {
            val intent = Intent(context, MacroEngineService::class.java).apply {
                this.action = action
                if (workflowId != null) putExtra(EXTRA_WORKFLOW_ID, workflowId)
            }
            context.startForegroundService(intent)
        }
    }
}
