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
    private val activeJobs = mutableMapOf<String, Job>()

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
            ACTION_REARM_ALL -> scope.launch { rearmAll() }
            ACTION_ENABLE -> intent.getStringExtra(EXTRA_WORKFLOW_ID)?.let { id ->
                scope.launch {
                    repository.setEnabled(true)
                    arm(id)
                }
            }
            ACTION_DISABLE -> intent.getStringExtra(EXTRA_WORKFLOW_ID)?.let { id ->
                scope.launch {
                    repository.setEnabled(false)
                    disarm(id)
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

    private suspend fun rearmAll() {
        val workflow = repository.load() ?: return
        if (workflow.enabled) arm(workflow.id)
        else if (activeJobs.isEmpty()) stopSelf()
    }

    private suspend fun arm(workflowId: String) {
        activeJobs.remove(workflowId)?.cancel()
        val workflow = repository.load() ?: return
        if (!workflow.enabled || workflow.id != workflowId) return
        val runner = WorkflowRunner(host, executionContext)
        activeJobs[workflowId] = runner.run(scope, workflow)
        refreshNotification()
    }

    private fun disarm(workflowId: String) {
        activeJobs.remove(workflowId)?.cancel()
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
