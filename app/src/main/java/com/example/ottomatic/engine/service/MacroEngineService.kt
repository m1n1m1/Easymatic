package com.example.ottomatic.engine.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.example.ottomatic.MainActivity
import com.example.ottomatic.R
import com.example.ottomatic.ServiceLocator
import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.LogSource
import com.example.ottomatic.core.service.RunFeedback
import com.example.ottomatic.core.service.SystemServices
import com.example.ottomatic.data.BootFailureStore
import com.example.ottomatic.data.WorkflowRepository
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.WorkflowRunner
import com.example.ottomatic.engine.runFromTrigger
import com.example.ottomatic.engine.trigger.ManualTrigger
import com.example.ottomatic.engine.trigger.TriggerHost
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

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

    /**
     * The handler is the process's backstop, not decoration: without one, anything
     * that escapes a coroutine here reaches the thread's default handler and kills
     * the app. [SupervisorJob] alone does not prevent that — it only stops sibling
     * cancellation.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            Log.e("Ottomatic", "Engine coroutine failed", e)
        },
    )

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
    private lateinit var systemServices: SystemServices

    override fun onCreate() {
        super.onCreate()
        host = ServiceLocator.triggerHost
        executionContext = ServiceLocator.executionContext
        repository = ServiceLocator.workflowRepository
        systemServices = ServiceLocator.systemServices
        // The notification carries the only way to stop a sound by hand, so it
        // has to follow playback rather than only arm/disarm.
        scope.launch { systemServices.soundPlaying.collect { refreshNotification() } }
        // The service has started successfully; clear the boot-failure flag so
        // MainActivity doesn't show a stale battery-optimisation prompt for a
        // start that actually worked.
        BootFailureStore.clear(this)
        _engineRunning.value = true
        startForegroundCompat(buildNotification(activeJobs.size))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A START_STICKY restart redelivers a *null* intent. Without this elvis the
        // service came back, promoted itself to the foreground in onCreate, and
        // armed nothing at all — a healthy-looking notification over an engine
        // holding no triggers, while every geofence, alarm and work item was still
        // registered against it. The reason the platform restarted us is that
        // macros are supposed to be armed, which is exactly what REARM_ALL means.
        dispatch(intent?.action ?: ACTION_REARM_ALL, intent)
        return START_STICKY
    }

    /**
     * Runs one start command, with [action] already resolved.
     *
     * Split from [onStartCommand] so that method says only what a null intent
     * means. Every branch below needs the intent's extras and is unreachable
     * without one, but the compiler cannot see that — hence [workflowIdOf], which
     * carries the null the old `when (intent?.action)` smart cast used to remove.
     */
    private fun dispatch(action: String, intent: Intent?) {
        when (action) {
            // One branch rather than two: the actions differ only in whether an
            // already-armed macro is left alone, and a null intent (the sticky
            // restart) reads as REARM_ALL, so it skips.
            ACTION_REARM_ALL, ACTION_REARM_CHANGED -> scope.launch {
                armMutex.withLock { rearmAll(skipArmed = action != ACTION_REARM_CHANGED) }
            }
            ACTION_ENABLE -> workflowIdOf(intent)?.let { id ->
                scope.launch {
                    repository.setEnabled(id, true)
                    armMutex.withLock { arm(id) }
                }
            }
            ACTION_DISABLE -> workflowIdOf(intent)?.let { id ->
                scope.launch {
                    repository.setEnabled(id, false)
                    armMutex.withLock { disarm(id) }
                }
            }
            // Re-read a macro that is *already* armed so a graph edit takes
            // effect without the user toggling it off and on. Deliberately does
            // not arm an unarmed macro: enabling is [ACTION_ENABLE]'s job, and a
            // RELOAD must never resurrect a macro the user just disabled.
            ACTION_RELOAD -> workflowIdOf(intent)?.let { id ->
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
            // A tap on a home-screen tile, a deck cell or a launcher shortcut.
            //
            // Deliberately independent of whether the macro is armed. `enabled` is
            // the user's intent to keep a macro listening for *background events*;
            // a tap is a foreground instruction and is not one of those, so a
            // macro whose switch is off still runs when its button is pressed —
            // exactly as the editor's Run button has always behaved. The tile shows
            // an "Off" marker so the state is visible rather than surprising.
            //
            // It therefore needs no [armMutex]: it starts nothing and stops
            // nothing, it only walks a graph.
            ACTION_RUN_MANUAL -> intent?.let { scope.launch { runManual(it) } }
            // The notification's "Stop sound" button. Silencing is immediate;
            // nothing here touches the armed macros.
            ACTION_STOP_SOUNDS -> {
                systemServices.stopSounds()
                refreshNotification()
                if (activeJobs.isEmpty()) stopSelf()
            }
        }
    }

    /** The macro an action names, or null — which every such branch already treats as "nothing to do". */
    private fun workflowIdOf(intent: Intent?): String? = intent?.getStringExtra(EXTRA_WORKFLOW_ID)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // A detached sound is held by this process, not by the run that started
        // it, so it would outlive the engine itself.
        systemServices.stopSounds()
        scope.cancel()
        _engineRunning.value = false
        _armedCount.value = 0
        super.onDestroy()
    }

    private suspend fun runManual(intent: Intent) {
        val workflowId = intent.getStringExtra(EXTRA_WORKFLOW_ID) ?: return
        val nodeId = intent.getStringExtra(EXTRA_NODE_ID) ?: return
        runManualTrigger(repository, executionContext, workflowId, nodeId)
        // This branch can have spun the service up for a macro that arms nothing —
        // a manual-only macro whose switch is off is the common case — and a
        // foreground service with an empty job map is a permanent notification for
        // a run that has already ended. Same guard ACTION_RELOAD makes.
        if (activeJobs.isEmpty()) stopSelf()
    }

    /**
     * Callers must hold [armMutex].
     *
     * Each macro is armed inside its own guard. This runs on cold start and on
     * boot, over every enabled macro at once, so a single one that cannot arm —
     * a config that no longer decodes, a platform source that refuses — used to
     * abort the loop and leave every macro *after* it in the list silently
     * unarmed, with nothing anywhere saying why.
     *
     * [skipArmed] is the difference between the two REARM actions, and it is
     * load-bearing in both directions: with it, two overlapping cold-start
     * re-arms cannot cancel each other's freshly started runners; without it, a
     * geofence place the user just moved would never actually move, because a
     * trigger reads its place only at activation.
     */
    @Suppress("TooGenericExceptionCaught") // One macro that cannot arm must not stop the rest.
    private suspend fun rearmAll(skipArmed: Boolean) {
        val enabled = repository.list().filter { it.enabled }
        if (enabled.isEmpty()) {
            if (activeJobs.isEmpty()) stopSelf()
            return
        }
        for (workflow in enabled) {
            if (skipArmed && activeJobs.containsKey(workflow.id)) continue
            try {
                arm(workflow.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Into that workflow's own console: "why won't this arm" is asked
                // in the editor, not in Logcat.
                executionContext.scoped(LogSource(workflow.id, LogSource.NO_RUN))
                    .log("Could not arm this workflow: ${e.message}", LogLevel.ERROR)
            }
        }
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
        val job = runner.run(scope, workflow, announceEnabled = announce)
        activeJobs[workflowId] = job
        // Nothing else ever removes a job that ended on its own, and one does: a
        // workflow with no trigger nodes finishes its body immediately, and the
        // notification then counts it as armed forever.
        //
        // The *two-argument* remove is load-bearing. With `remove(workflowId)`, a
        // stale job's completion callback firing after a re-arm would delete the
        // new job's entry — orphaning a runner still collecting its triggers that
        // nothing, including a disable/enable cycle, could then cancel. That is
        // precisely the failure [armMutex] exists to prevent.
        job.invokeOnCompletion {
            if (activeJobs.remove(workflowId, job)) refreshNotification()
        }
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
        // Cancelling the run stops a sound it was waiting on, but a sound
        // started fire-and-forget belongs to the process and would play on
        // after the macro that asked for it is gone.
        systemServices.stopSounds()
        refreshNotification()
        if (activeJobs.isEmpty()) stopSelf()
    }

    /**
     * Republishes the armed count, to the notification and to [armedCount].
     *
     * The status widget reads the second for the same reason the notification shows
     * the first, so they are refreshed from one place: two counters for one fact
     * that update on different events is how a home screen ends up claiming eight
     * macros are armed while the notification says two.
     */
    private fun refreshNotification() {
        val count = activeJobs.size
        _armedCount.value = count
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(count))
    }

    private fun buildNotification(activeCount: Int): Notification {
        val text = if (activeCount == 0) "Standing by" else "$activeCount macro(s) armed"
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Ottomatic")
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
        // Offered only while there is something to stop: a button that usually
        // does nothing teaches people to ignore it.
        if (systemServices.soundPlaying.value) builder.addAction(stopSoundAction())
        return builder.build()
    }

    /**
     * Opens the app on a tap. An ongoing notification is the app's only visible
     * trace while it is backgrounded, so tapping it should lead back in.
     *
     * `SINGLE_TOP` rather than the `CLEAR_TOP` [com.example.ottomatic.data.BootFailureNotifier]
     * uses: the whole app is one Activity holding a Compose back stack, so
     * clearing the top would tear that Activity down and rebuild it — the tap
     * would throw the user out of the editor they had open instead of returning
     * them to it. There is nothing above MainActivity to clear anyway.
     */
    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            OPEN_APP_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun stopSoundAction(): Notification.Action {
        val intent = Intent(this, MacroEngineService::class.java).setAction(ACTION_STOP_SOUNDS)
        val pending = PendingIntent.getService(
            this,
            STOP_SOUND_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, "Stop sound", pending).build()
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
        /**
         * Make sure every enabled macro is armed, leaving the ones that already
         * are alone.
         *
         * The cold-start / boot / geofence-transition action. Several of those
         * routinely overlap — a geofence broadcast spawns the process, so
         * `OttomaticApplication` and `GeofenceReceiver` both send this within
         * milliseconds — and re-arming an already-armed macro cancels and joins
         * its runner, which would kill the run a just-delivered transition had
         * started. Use [ACTION_REARM_CHANGED] when the point *is* to re-arm.
         */
        const val ACTION_REARM_ALL = "com.example.ottomatic.action.REARM_ALL"

        /**
         * Re-arm every enabled macro, armed or not.
         *
         * For a library edit — a geofence place moved, a global variable renamed —
         * where the whole purpose is to make running macros pick the change up.
         * A trigger reads its place once, at activation, so nothing short of a
         * genuine re-arm moves a fence.
         */
        const val ACTION_REARM_CHANGED = "com.example.ottomatic.action.REARM_CHANGED"
        const val ACTION_ENABLE = "com.example.ottomatic.action.ENABLE"
        const val ACTION_DISABLE = "com.example.ottomatic.action.DISABLE"
        const val ACTION_RELOAD = "com.example.ottomatic.action.RELOAD"
        const val ACTION_STOP_SOUNDS = "com.example.ottomatic.action.STOP_SOUNDS"
        const val ACTION_RUN_MANUAL = "com.example.ottomatic.action.RUN_MANUAL"
        const val EXTRA_WORKFLOW_ID = "workflowId"
        const val EXTRA_NODE_ID = "nodeId"

        /**
         * Whether the engine service is alive, for the status widget's dot.
         *
         * A `StateFlow` set from the service's own lifecycle rather than a
         * `getRunningServices` query: that API has been deprecated since API 26 and
         * returns only this app's own services anyway, so it would be a slower way
         * of asking a question the service can simply answer about itself.
         *
         * Companion-scoped because the reader is a widget, which has no instance to
         * ask and may well be composing while the service is dead — which is
         * precisely the state it needs to render.
         */
        private val _engineRunning = MutableStateFlow(false)
        val engineRunning: StateFlow<Boolean> = _engineRunning.asStateFlow()

        private val _armedCount = MutableStateFlow(0)
        val armedCount: StateFlow<Int> = _armedCount.asStateFlow()

        private const val NOTIFICATION_ID = 4242
        private const val CHANNEL_ID = "ottomatic.engine"
        private const val OPEN_APP_REQUEST_CODE = 0
        private const val STOP_SOUND_REQUEST_CODE = 1

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

        /**
         * Runs one manual trigger, from a widget tap or a launcher shortcut.
         *
         * Wrapped, and with a fallback, because this is the one caller that starts
         * the service from outside the app's own UI. Android 12+ forbids starting a
         * foreground service from the background, and while interacting with a
         * widget *is* on the exemption list, the exemption is a short window and
         * OEM builds are not uniform about it. A tap that throws
         * `ForegroundServiceStartNotAllowedException` would otherwise be a button
         * that does nothing and says nothing.
         *
         * The fallback runs the macro on [appScope] instead. That loses the
         * service's protection against the process being reaped mid-run, which
         * matters for a macro with a long `action.delay` in it — but a short macro,
         * which is what people put on a home-screen button, completes long before
         * that becomes a question, and running it is strictly better than dropping
         * the tap.
         */
        @Suppress("TooGenericExceptionCaught") // Platform throws vary by OEM; any of them means "fall back".
        fun runManual(context: Context, workflowId: String, nodeId: String) {
            val intent = Intent(context, MacroEngineService::class.java).apply {
                action = ACTION_RUN_MANUAL
                putExtra(EXTRA_WORKFLOW_ID, workflowId)
                putExtra(EXTRA_NODE_ID, nodeId)
            }
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.w("Ottomatic", "Could not start the engine for a manual run; running in-process", e)
                ServiceLocator.appScope.launch {
                    runManualTrigger(
                        ServiceLocator.workflowRepository,
                        ServiceLocator.executionContext,
                        workflowId,
                        nodeId,
                    )
                }
            }
        }

        /**
         * Runs one `trigger.manual` node once, and reports the outcome to
         * [RunFeedback] so the tile that started it can stop saying "Running…".
         *
         * Takes its two collaborators as parameters rather than reading them off a
         * service instance, because both callers need it and only one of them *is*
         * a service: the [ACTION_RUN_MANUAL] branch passes the service's own, and
         * the [runManual] fallback passes [ServiceLocator]'s.
         *
         * Every lookup failure here is silent on purpose. A widget outlives the
         * thing it points at — a macro can be deleted, or its manual trigger removed
         * from the graph, while a tile for it sits on the home screen — and the
         * honest answer to a tap on a stale tile is that nothing runs. Reporting it
         * as a *failed run* would put a red cross on a button that never had a
         * chance to fail, and the widgets already redraw a stale tile into its
         * "Missing" state on the next repository change.
         */
        private suspend fun runManualTrigger(
            repository: WorkflowRepository,
            context: ExecutionContext,
            workflowId: String,
            nodeId: String,
        ) {
            val workflow = repository.load(workflowId) ?: return
            val node = workflow.node(NodeId(nodeId))
                ?.takeIf { it.typeId == ManualTrigger.TYPE_ID }
                ?: return
            val label = node.config[ConfigKey(ManualTrigger.LABEL_KEY)]
                ?.takeIf { it.isNotBlank() }
                ?: node.name
            val target = RunFeedback.Target(workflowId, nodeId, workflow.name, label)
            RunFeedback.running(target, System.currentTimeMillis())
            val ok = runFromTrigger(context, workflow, node)
            RunFeedback.finished(target, ok, System.currentTimeMillis())
        }
    }
}
