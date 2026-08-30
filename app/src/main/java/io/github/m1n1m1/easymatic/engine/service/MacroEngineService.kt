package io.github.m1n1m1.easymatic.engine.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import io.github.m1n1m1.easymatic.MainActivity
import io.github.m1n1m1.easymatic.R
import io.github.m1n1m1.easymatic.ServiceLocator
import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.core.service.LogSource
import io.github.m1n1m1.easymatic.core.service.RunFeedback
import io.github.m1n1m1.easymatic.core.service.SystemServices
import io.github.m1n1m1.easymatic.core.trigger.TriggerBus
import io.github.m1n1m1.easymatic.data.BootFailureStore
import io.github.m1n1m1.easymatic.data.WorkflowRepository
import io.github.m1n1m1.easymatic.data.service.ServiceForeground
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.PendingWaits
import io.github.m1n1m1.easymatic.engine.api.ApiRun
import io.github.m1n1m1.easymatic.engine.WorkflowRunner
import io.github.m1n1m1.easymatic.engine.runFromTrigger
import io.github.m1n1m1.easymatic.engine.trigger.ManualTrigger
import io.github.m1n1m1.easymatic.engine.trigger.TriggerHost
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
 * [io.github.m1n1m1.easymatic.engine.WorkflowRunner.run] collects each trigger's
 * event [Flow] on the [CoroutineScope] it is handed. Previously the only scope
 * was the editor's `viewModelScope`, which is cancelled on Activity destruction
 * — so trigger subscriptions died the moment the user backgrounded the app, and
 * manifest receivers' [io.github.m1n1m1.easymatic.core.trigger.TriggerBus] emits were
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
            Log.e("Easymatic", "Engine coroutine failed", e)
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

    /**
     * How `action.camera_photo` borrows the camera foreground-service type.
     *
     * A property rather than a lambda passed inline so [onDestroy] can hand the *same*
     * instance back: a service that has already been recreated must not have its
     * successor's registration cleared by the teardown of the one it replaced.
     */
    private val foregroundTypes = ServiceForeground.Types { claimed -> setForegroundTypes(claimed) }

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
        // Opened for every configured hub, independent of what is armed — see HubLink.
        // A push connection is not an arm: it also keeps the cache `value.ha_state`
        // reads, which every macro may pull whether or not it has a hub trigger in it.
        ServiceLocator.hubLink.start()
        _engineRunning.value = true
        startForegroundCompat(buildNotification(activeJobs.size))
        // Published last, so nothing can promote the type before there is a foreground
        // notification to re-post it with.
        ServiceForeground.attach(foregroundTypes)
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
                        } else {
                            // startForegroundService spun us up for nothing.
                            stopIfIdle()
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
            // A call through the process API. Same reasoning as the branch above for
            // needing no [armMutex] — it walks a graph and arms nothing — but unlike
            // a tap it *is* gated on the macro being enabled, which
            // [ApiRun.runApiTrigger] checks. Somebody pressing a tile can see the
            // macro is off and means it anyway; an app three processes away cannot.
            ACTION_RUN_API -> intent?.let { scope.launch { runApi(it) } }
            // The notification's "Stop sound" button. Silencing is immediate;
            // nothing here touches the armed macros.
            ACTION_STOP_SOUNDS -> {
                systemServices.stopSounds()
                refreshNotification()
                stopIfIdle()
            }
        }
    }

    /** The macro an action names, or null — which every such branch already treats as "nothing to do". */
    private fun workflowIdOf(intent: Intent?): String? = intent?.getStringExtra(EXTRA_WORKFLOW_ID)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Cleared first: a capture that promotes the type after this point would re-post a
        // foreground notification for a service on its way out.
        ServiceForeground.detach(foregroundTypes)
        // A detached sound is held by this process, not by the run that started
        // it, so it would outlive the engine itself.
        systemServices.stopSounds()
        // Held open for the engine's lifetime, so it ends with it. Nothing else would
        // close it: it is not owned by any arm, which is the whole point of it.
        ServiceLocator.hubLink.stop()
        scope.cancel()
        _engineRunning.value = false
        _armedCount.value = 0
        super.onDestroy()
    }

    private suspend fun runManual(intent: Intent) {
        val workflowId = intent.getStringExtra(EXTRA_WORKFLOW_ID) ?: return
        val nodeId = intent.getStringExtra(EXTRA_NODE_ID) ?: return
        runManualTrigger(repository, executionContext, workflowId, nodeId, ServiceLocator.appScope)
        holdForPendingWaits()
    }

    private suspend fun runApi(intent: Intent) {
        val workflowId = intent.getStringExtra(EXTRA_WORKFLOW_ID) ?: return
        val nodeId = intent.getStringExtra(EXTRA_NODE_ID) ?: return
        ApiRun.runApiTrigger(
            repository = repository,
            context = executionContext,
            workflowId = workflowId,
            nodeId = nodeId,
            eventJson = intent.getStringExtra(EXTRA_TRIGGER_DATA),
            caller = intent.getStringExtra(EXTRA_CALLER).orEmpty(),
            deferredScope = ServiceLocator.appScope,
        )
        holdForPendingWaits()
    }

    /**
     * Stays up for a `Wait Until` this run set, then stops.
     *
     * The foreground service is the only thing between a pending wait and the
     * process being reaped, and both the manual and the API paths can leave one
     * behind on a macro that arms nothing at all. The loop ends as soon as the wait
     * does, or as soon as something else arms and takes over the reason to run.
     *
     * Shared rather than written twice: the two callers have identical needs, and a
     * copy would be the one that stopped getting fixed.
     */
    private suspend fun holdForPendingWaits() {
        while (activeJobs.isEmpty() && PendingWaits.count > 0) delay(IDLE_POLL_MS)
        stopIfIdle()
    }

    /**
     * Stops the service when there is nothing left for it to hold up.
     *
     * A manual run can have spun the service up for a macro that arms nothing — a
     * manual-only macro whose switch is off is the common case — and a foreground
     * service with an empty job map is a permanent notification for a run that has
     * already ended.
     *
     * A pending `Wait Until` counts as something left to do even though it arms no
     * trigger: half the macro has yet to happen. It is not enough on its own to
     * *keep* the service up — nothing here re-checks — which is why the one path
     * that can create a wait with nothing armed waits for it in [runManual].
     */
    private fun stopIfIdle() {
        if (activeJobs.isEmpty() && PendingWaits.count == 0) stopSelf()
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
    private suspend fun rearmAll(skipArmed: Boolean) {
        // Closes TriggerBus's waking-up window on every way out, including the
        // no-macros-enabled one: an event parked for an engine that then armed
        // nothing has nobody left to arrive and take it.
        try {
            rearmEnabled(skipArmed)
        } finally {
            TriggerBus.engineReady()
        }
    }

    @Suppress("TooGenericExceptionCaught") // One macro that cannot arm must not stop the rest.
    private suspend fun rearmEnabled(skipArmed: Boolean) {
        val enabled = repository.list().filter { it.enabled }
        if (enabled.isEmpty()) {
            stopIfIdle()
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
        // Any wait this macro had is already gone: cancelling the arm cancels its
        // deferred branches, and the `join` above is what makes that true by now
        // rather than shortly afterwards. What is left belongs to somebody else.
        stopIfIdle()
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
            .setContentTitle("Easymatic")
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
     * `SINGLE_TOP` rather than the `CLEAR_TOP` [io.github.m1n1m1.easymatic.data.BootFailureNotifier]
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

    /**
     * Promotes the service to the foreground, optionally borrowing some hardware types.
     *
     * Three branches rather than two, and the middle one is the whole addition. **Below API
     * 34 the two-argument call is kept for the ordinary case**, because that is what confers
     * the *manifest-declared* types — replacing it with an explicit mask would hand the
     * platform a zero on API 29–33 and quietly drop what the manifest says. The explicit
     * form is used there only while a capture or a recording is running, which is the one
     * moment this service has something to name.
     */
    @Suppress("CallApiLevelMismatch") // The type constants are API 29/34; guarded at runtime.
    private fun startForegroundCompat(
        notification: Notification,
        borrowed: Set<ServiceForeground.Kind> = emptySet(),
    ) {
        ensureChannel()
        val mask = borrowed.fold(0) { acc, kind -> acc or kind.serviceType }
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or mask,
                )

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mask != 0 ->
                startForeground(NOTIFICATION_ID, notification, mask)

            else -> startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Applies the borrowed foreground-service types, answering which of them are now held.
     *
     * From Android 11 a foreground service's access to the camera and the microphone
     * follows its declared *type* rather than the app's grant, so `action.camera_photo` and
     * the three recording actions borrow one for as long as they hold the hardware. Neither
     * can be claimed permanently — see [ServiceForeground] — and the borrowing is what keeps
     * the claim honest.
     *
     * **The permission check is not defensive, it is the crash guard.** From API 34
     * `startForeground` throws when a named type's permission is not held, and this runs
     * inside the service that owns every armed macro: promoting unconditionally would kill
     * the engine on every phone whose owner granted neither, for the sake of a few nodes.
     * Dropping the unheld type here instead lets the capture or the recording report a
     * sentence and the node pulse `out`.
     */
    @Suppress("CallApiLevelMismatch") // The types are only ever named from API 29 up.
    private fun setForegroundTypes(claimed: Set<ServiceForeground.Kind>): Set<ServiceForeground.Kind> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptySet()
        val held = claimed.filterTo(mutableSetOf()) { holds(it) }
        startForegroundCompat(buildNotification(activeJobs.size), held)
        return held
    }

    /** Whether the runtime grant behind a borrowable type is actually held right now. */
    private fun holds(kind: ServiceForeground.Kind): Boolean {
        val permission = when (kind) {
            ServiceForeground.Kind.CAMERA -> android.Manifest.permission.CAMERA
            ServiceForeground.Kind.MICROPHONE -> android.Manifest.permission.RECORD_AUDIO
        }
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Easymatic engine",
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
         * `EasymaticApplication` and `GeofenceReceiver` both send this within
         * milliseconds — and re-arming an already-armed macro cancels and joins
         * its runner, which would kill the run a just-delivered transition had
         * started. Use [ACTION_REARM_CHANGED] when the point *is* to re-arm.
         */
        const val ACTION_REARM_ALL = "io.github.m1n1m1.easymatic.action.REARM_ALL"

        /**
         * Re-arm every enabled macro, armed or not.
         *
         * For a library edit — a geofence place moved, a global variable renamed —
         * where the whole purpose is to make running macros pick the change up.
         * A trigger reads its place once, at activation, so nothing short of a
         * genuine re-arm moves a fence.
         */
        const val ACTION_REARM_CHANGED = "io.github.m1n1m1.easymatic.action.REARM_CHANGED"
        const val ACTION_ENABLE = "io.github.m1n1m1.easymatic.action.ENABLE"
        const val ACTION_DISABLE = "io.github.m1n1m1.easymatic.action.DISABLE"
        const val ACTION_RELOAD = "io.github.m1n1m1.easymatic.action.RELOAD"
        const val ACTION_STOP_SOUNDS = "io.github.m1n1m1.easymatic.action.STOP_SOUNDS"
        const val ACTION_RUN_MANUAL = "io.github.m1n1m1.easymatic.action.RUN_MANUAL"

        /**
         * Run one `trigger.api` node, on behalf of a caller the front door has
         * already authorised.
         *
         * Internal, and unrelated to
         * [io.github.m1n1m1.easymatic.domain.model.ApiContract.ACTION_RUN], which is the
         * *exported* broadcast another app sends. The two are deliberately separate
         * strings: this service is `exported="false"` and every authorisation
         * decision has already been taken by the time an intent carrying this action
         * exists, so letting an outside caller name it directly would be handing
         * them the answer to a question nobody asked them.
         */
        const val ACTION_RUN_API = "io.github.m1n1m1.easymatic.action.RUN_API"
        const val EXTRA_WORKFLOW_ID = "workflowId"
        const val EXTRA_NODE_ID = "nodeId"

        /** The caller's values as a `TriggerEventWire` JSON string; see [ApiRun]. */
        const val EXTRA_TRIGGER_DATA = "triggerData"

        /** Who asked, for the run log. A package name, or blank for the broadcast door. */
        const val EXTRA_CALLER = "caller"

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

        /** How often [runManual] re-checks whether the wait it is holding up for is done. */
        private const val IDLE_POLL_MS = 60_000L

        private const val NOTIFICATION_ID = 4242
        private const val CHANNEL_ID = "easymatic.engine"
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
                Log.w("Easymatic", "Could not start the engine for a manual run; running in-process", e)
                ServiceLocator.appScope.launch {
                    runManualTrigger(
                        ServiceLocator.workflowRepository,
                        ServiceLocator.executionContext,
                        workflowId,
                        nodeId,
                        ServiceLocator.appScope,
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
         * [deferredScope] is where a `Wait Until` on this graph parks its second
         * branch, and both callers pass [ServiceLocator.appScope] rather than the
         * service's own. A manual run has no *arm* behind it — the switch may well
         * be off — so the service is free to stop the moment this returns, and
         * parking the wait on a scope that is about to be cancelled would cancel
         * the wait. The process is the honest owner here. See [stopIfIdle] for the
         * other half of keeping it alive.
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
            deferredScope: CoroutineScope,
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
            val ok = runFromTrigger(context, workflow, node, deferredScope = deferredScope)
            RunFeedback.finished(target, ok, System.currentTimeMillis())
        }
    }
}
