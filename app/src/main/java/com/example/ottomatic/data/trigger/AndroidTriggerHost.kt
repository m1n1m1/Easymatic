package com.example.ottomatic.data.trigger

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.annotation.SuppressLint
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.ottomatic.ServiceLocator
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.service.CalendarLimits
import com.example.ottomatic.core.service.Calendars
import com.example.ottomatic.core.service.Contacts
import com.example.ottomatic.core.service.EventQuery
import com.example.ottomatic.data.calendar.CalendarWatchers
import com.example.ottomatic.data.images.ImageWatchers
import com.example.ottomatic.core.trigger.TriggerBus
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.data.GeofencePlaceRepository
import com.example.ottomatic.data.MailAccountRepository
import com.example.ottomatic.data.SmartHomeHubRepository
import com.example.ottomatic.data.homeassistant.HaConnections
import com.example.ottomatic.data.mqtt.MqttConnections
import com.example.ottomatic.data.NfcTagRepository
import com.example.ottomatic.data.mail.MailWatchers
import com.example.ottomatic.data.nfc.NfcReader
import com.example.ottomatic.data.sensor.SensorBridge
import com.example.ottomatic.data.service.AndroidContacts
import com.example.ottomatic.domain.model.GeofencePlace
import com.example.ottomatic.domain.model.MailAccount
import com.example.ottomatic.domain.model.SmartHomeHub
import com.example.ottomatic.domain.model.NfcTag
import com.example.ottomatic.engine.trigger.BatteryDirection
import com.example.ottomatic.engine.trigger.CalendarOccurrence
import com.example.ottomatic.engine.trigger.CalendarWatchSpec
import com.example.ottomatic.engine.trigger.ImageWatchSpec
import com.example.ottomatic.engine.trigger.planNext
import com.example.ottomatic.engine.trigger.GeofenceArmResult
import com.example.ottomatic.engine.trigger.GeofencePresence
import com.example.ottomatic.engine.trigger.GeofenceRegistration
import com.example.ottomatic.engine.trigger.GeofenceTransition
import com.example.ottomatic.engine.trigger.RegisteredFence
import com.example.ottomatic.engine.trigger.HaWatchSpec
import com.example.ottomatic.engine.trigger.MailWatchSpec
import com.example.ottomatic.engine.trigger.MqttWatchSpec
import com.example.ottomatic.engine.trigger.NfcStatus
import com.example.ottomatic.engine.trigger.ScheduleHandle
import com.example.ottomatic.engine.trigger.ScreenOffMode
import com.example.ottomatic.engine.trigger.SensorKind
import com.example.ottomatic.engine.trigger.SensorRate
import com.example.ottomatic.engine.trigger.SensorSample
import com.example.ottomatic.engine.trigger.TriggerHost
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit

private const val MS_PER_MINUTE = 60_000L
private const val MS_PER_DAY = 24L * 60 * 60 * 1000

/** See the comment on `setNotificationResponsiveness` in `armGeofence`. */
private const val GEOFENCE_RESPONSIVENESS_MS = 30_000 // half of MS_PER_MINUTE

/**
 * Android implementation of [TriggerHost]. Supplies real system streams and
 * arms [ScheduleWorker] via WorkManager, and geofences via Play Services.
 *
 * [geofencePlaces] is the shared place library the geofence trigger resolves
 * its configured place against; it is read synchronously from the repository's
 * in-memory cache, because arming happens outside a suspending context.
 */
// One override per TriggerHost capability and one constructor parameter per library a
// trigger resolves against; the interface sets both counts.
@Suppress("TooManyFunctions", "LongParameterList")
class AndroidTriggerHost(
    context: Context,
    private val geofencePlaces: GeofencePlaceRepository,
    /** The tag library `trigger.nfc` resolves a scanned id's name against. */
    private val nfcTags: NfcTagRepository,
    /**
     * Shared with the execution context's value nodes, so a `value.orientation`
     * read and an armed orientation trigger use one platform registration
     * rather than two. Defaults to its own for callers that only need triggers.
     */
    private val sensorBridge: SensorBridge = SensorBridge(context),
    /**
     * The address book `trigger.sms`'s sender filter resolves a chosen contact
     * against. Shared with the execution context so an action and a trigger see one
     * instance; defaults to its own for callers that only need triggers.
     */
    private val contacts: Contacts = AndroidContacts(context),
    /**
     * The account library `trigger.mail` resolves its chosen account against.
     * Null for callers that only need the other triggers, which then leaves every
     * mail trigger unarmed rather than watching nothing.
     */
    private val mailAccounts: MailAccountRepository? = null,
    /**
     * The hub library the two Home Assistant triggers and `trigger.mqtt_message` resolve
     * their chosen hub against, and the connections they register with.
     *
     * All null for callers that only need the other triggers, which then leaves every one
     * of those triggers unarmed rather than watching nothing — [mailAccounts]' rule, for
     * its reason.
     *
     * One repository and **two** connection managers, which is the shape rather than an
     * oversight: a hub is a hub whatever it speaks, and what differs is the protocol
     * spoken to it.
     */
    private val smartHomeHubs: SmartHomeHubRepository? = null,
    private val haConnections: HaConnections? = null,
    private val mqttConnections: MqttConnections? = null,
    /**
     * The same instance the execution context holds, on `contacts`' reasoning: an action
     * reading an appointment and a trigger planning an alarm against one must not disagree
     * about it.
     *
     * Nullable on `mailAccounts`' shape — null leaves every calendar trigger unarmed and
     * silent rather than watching nothing, which is what a test double wants.
     */
    private val calendars: Calendars? = null,
    /**
     * Where a coalesced calendar notification is published from. Defaults to the
     * application scope for the same reason the watchers are owned here at all: the
     * observer outlives any one arm's coroutine.
     */
    private val calendarScope: CoroutineScope = ServiceLocator.appScope,
) : TriggerHost {

    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val geofencingClient = LocationServices.getGeofencingClient(appContext)
    private val presenceStore = GeofencePresenceStore(appContext)
    private val registrationStore = GeofenceRegistrationStore(appContext)
    private val lifecycleBridge = AppLifecycleBridge(appContext)

    @Suppress("UnusedPrivateProperty") // Kept alive so its receiver stays registered.
    private val screenBridge = ScreenBroadcastBridge(appContext)

    // Registered here rather than per-arm so its first observation lands while
    // nothing is collecting: a NetworkCallback replays the current network on
    // registration, and that replay must be absorbed rather than reported.
    @Suppress("UnusedPrivateProperty") // Kept alive so its callback stays registered.
    private val wifiNetworkBridge = WifiNetworkBridge(appContext)

    // One per process, holding the poll registrations and (once IDLE lands) the
    // connections, reference-counted per account. Owned here for SensorBridge's
    // reason: a trigger is handed a host and nothing else.
    private val mailWatchers = MailWatchers(appContext, mailAccounts)

    // One per process, holding the single ContentObserver and the table of nodes that
    // asked for it. Owned here on `mailWatchers`' reasoning.
    private val calendarWatchers = CalendarWatchers(appContext, calendarScope)

    // Likewise one per process. Shares `calendarScope` rather than taking a scope of
    // its own: both are debounce timers that must outlive any single arm, and a second
    // scope would be a second thing to remember to cancel.
    private val imageWatchers = ImageWatchers(appContext, calendarScope)

    override fun mailAccount(id: String): MailAccount? = mailAccounts?.get(id)

    /**
     * Reads the look-ahead window and hands it to the planner.
     *
     * The window starts *before* [afterEpochMs] when the node fires after an appointment
     * rather than before it — a negative lead. Without that, "ten minutes after my last
     * meeting ends" would miss whenever the trigger re-planned inside those ten minutes,
     * because a meeting that has already finished no longer overlaps the window and the
     * provider would not return it at all.
     */
    override suspend fun nextCalendarOccurrence(
        spec: CalendarWatchSpec,
        afterEpochMs: Long,
    ): CalendarOccurrence? {
        val calendars = calendars ?: return null
        val trailing = (-spec.leadMinutes).coerceAtLeast(0) * MS_PER_MINUTE
        val listing = calendars.events(
            EventQuery(
                calendarSpec = spec.calendarSpec,
                fromEpochMs = afterEpochMs - trailing,
                untilEpochMs = afterEpochMs + CalendarLimits.HORIZON_DAYS * MS_PER_DAY,
                titleContains = spec.titleContains,
                limit = CalendarLimits.MAX_EVENTS,
            ),
        )
        return planNext(spec, listing.events, afterEpochMs)
    }

    override fun armCalendarWatch(
        nodeId: NodeId,
        onReport: (String, LogLevel) -> Unit,
    ): ScheduleHandle = calendarWatchers.arm(nodeId, onReport)

    override fun armImageWatch(
        nodeId: NodeId,
        spec: ImageWatchSpec,
        onReport: (String, LogLevel) -> Unit,
    ): ScheduleHandle = imageWatchers.arm(nodeId, spec, onReport)

    override fun armMailWatch(
        nodeId: NodeId,
        accountId: String,
        spec: MailWatchSpec,
        onReport: (String, LogLevel) -> Unit,
    ): ScheduleHandle = mailWatchers.arm(nodeId, accountId, spec, onReport)

    override fun smartHomeHub(id: String): SmartHomeHub? = smartHomeHubs?.get(id)

    override fun armHomeAssistantWatch(
        nodeId: NodeId,
        spec: HaWatchSpec,
        onReport: (String, LogLevel) -> Unit,
    ): ScheduleHandle =
        // Not owned here, unlike `mailWatchers`: the connections outlive any arm and
        // are held for the engine's lifetime, so this host is handed the manager rather
        // than constructing one. See HubLink.
        haConnections?.arm(nodeId, spec.hubId, spec, onReport) ?: ScheduleHandle { }

    override fun armMqttWatch(
        nodeId: NodeId,
        spec: MqttWatchSpec,
        onReport: (String, LogLevel) -> Unit,
    ): ScheduleHandle =
        // Not owned here for [armHomeAssistantWatch]'s reason: the connection outlives any
        // arm, because it also keeps the cache `value.mqtt_topic` reads. See HubLink.
        mqttConnections?.arm(nodeId, spec, onReport) ?: ScheduleHandle { }

    override fun sensorSamples(kind: SensorKind, rate: SensorRate): Flow<SensorSample> =
        sensorBridge.samples(kind, rate)

    override fun armSignificantMotion(nodeId: NodeId): ScheduleHandle =
        sensorBridge.armSignificantMotion(nodeId)

    override fun armScreenOffSensing(mode: ScreenOffMode): ScheduleHandle =
        sensorBridge.armScreenOffSensing(mode)

    override fun sensorMaximumRange(kind: SensorKind): Float? = sensorBridge.maximumRange(kind)

    override fun armSchedule(
        nodeId: NodeId,
        intervalMinutes: Long,
    ): ScheduleHandle {
        // WorkManager enforces a 15-minute minimum; clamp here for clarity.
        val minutes = intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES)
        val request = PeriodicWorkRequestBuilder<ScheduleWorker>(minutes, TimeUnit.MINUTES)
            .setInputData(workDataOf(ScheduleWorker.KEY_NODE_ID to nodeId))
            .build()
        val workName = ScheduleWorker.WORK_NAME_PREFIX + nodeId
        workManager.enqueueUniquePeriodicWork(workName, ExistingPeriodicWorkPolicy.UPDATE, request)
        return ScheduleHandle { workManager.cancelUniqueWork(workName) }
    }

    override fun armAlarm(nodeId: NodeId, atEpochMs: Long): ScheduleHandle {
        val pendingIntent = alarmPendingIntent(nodeId)
        alarmManager.setWakeup(atEpochMs, pendingIntent, "node $nodeId")
        return ScheduleHandle { alarmManager.cancel(pendingIntent) }
    }

    private fun alarmPendingIntent(nodeId: NodeId): PendingIntent {
        val intent = Intent(appContext, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_ALARM
            putExtra(AlarmReceiver.EXTRA_NODE_ID, nodeId.value)
        }
        val requestCode = nodeId.hashCode() and Int.MAX_VALUE
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(appContext, requestCode, intent, flags)
    }

    override fun armBatteryLevelPoll(
        nodeId: NodeId,
        intervalMinutes: Long,
        direction: BatteryDirection,
        threshold: Int,
    ): ScheduleHandle {
        val minutes = intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES)
        val request = PeriodicWorkRequestBuilder<BatteryLevelWorker>(minutes, TimeUnit.MINUTES)
            .setInputData(
                workDataOf(
                    BatteryLevelWorker.KEY_NODE_ID to nodeId,
                    BatteryLevelWorker.KEY_DIRECTION to direction.name,
                    BatteryLevelWorker.KEY_LEVEL to threshold,
                ),
            )
            .build()
        val workName = BatteryLevelWorker.WORK_NAME_PREFIX + nodeId
        workManager.enqueueUniquePeriodicWork(workName, ExistingPeriodicWorkPolicy.UPDATE, request)
        return ScheduleHandle { workManager.cancelUniqueWork(workName) }
    }

    // The one host that serves the real bus, so the one that can hand over what
    // was held for a node while this process was starting.
    override fun busEventsFor(nodeId: NodeId): Flow<com.example.ottomatic.core.trigger.TriggerEvent> =
        TriggerBus.eventsFor(nodeId)

    override fun geofencePlace(id: String): GeofencePlace? = geofencePlaces.get(id)

    override fun nfcTag(uid: String): NfcTag? = nfcTags.get(uid)

    // Deliberately does not report "switched off": that one the Permissions screen
    // and the node's own card already say, and saying it a third time in the
    // console on every arm would be noise.
    override fun nfcStatus(): NfcStatus = when {
        !NfcReader.isAvailable(appContext) -> NfcStatus.NO_HARDWARE
        !NfcReader.tagIntentsAllowed(appContext) -> NfcStatus.TAG_INTENTS_BLOCKED
        else -> NfcStatus.OK
    }

    override fun contactNumber(lookupKey: String): String? = contacts.phoneNumber(lookupKey)

    @SuppressLint("MissingPermission")
    // Three ways out, and each is a different answer: refused for want of a grant,
    // nothing to do because the fence is already there, and the registration itself.
    @Suppress("ReturnCount")
    override fun armGeofence(
        nodeId: NodeId,
        latitude: Double,
        longitude: Double,
        radiusMeters: Float,
        transitions: Set<GeofenceTransition>,
        dwellDelayMs: Int,
        onResult: (GeofenceArmResult) -> Unit,
    ): ScheduleHandle {
        // Asked before the request is built, not after it is refused. Play
        // Services answers a missing grant with DEVELOPER_ERROR — a code that
        // names the developer rather than the thing the *user* has to change —
        // so the one failure with an obvious fix would otherwise be the one
        // reported least usefully.
        missingLocationPermission()?.let { reason ->
            Log.w(TAG, "not arming geofence for node $nodeId: $reason")
            onResult(GeofenceArmResult.Refused(GeofenceStatusCodes.GEOFENCE_INSUFFICIENT_LOCATION_PERMISSION, reason))
            return ScheduleHandle { }
        }
        val spec = GeofenceRegistration.fingerprint(latitude, longitude, radiusMeters, transitions, dwellDelayMs)
        val pendingIntent = geofencePendingIntent(nodeId)
        // The fence this node wants is the fence already registered, and the process
        // has merely restarted. Re-adding it would reset its state inside Play
        // Services, which re-evaluates and announces the result — and "outside" is
        // announced as an ordinary EXIT. Since a re-arm happens on every process
        // start and the process is reaped and resurrected all night, that
        // announcement *is* the three-in-the-morning bug, generated on a timer by
        // this app. Platform fences outlive the process, so there is nothing to do.
        if (GeofenceRegistration.stillStands(registrationStore.remembered(nodeId.value), spec, bootedAt(), now())) {
            Log.i(TAG, "geofence for node $nodeId is already registered; not re-adding")
            onResult(GeofenceArmResult.Registered)
            return ScheduleHandle { releaseGeofence(nodeId, pendingIntent) }
        }
        val transitionTypes = transitions.fold(0) { acc, t -> acc or t.toGmsConstant() }
        val geofence = Geofence.Builder()
            .setRequestId(nodeId.value)
            .setCircularRegion(latitude, longitude, radiusMeters)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(transitionTypes)
            // Only alongside DWELL. The loitering delay *is* the gap between ENTER
            // and DWELL, so on a fence that does not watch for DWELL it describes
            // an alert that can never be sent — the two are one setting, and the
            // API pairs them.
            .apply { if (GeofenceTransition.DWELL in transitions) setLoiteringDelay(dwellDelayMs) }
            // How long Play Services may sit on a transition before telling us, and
            // the default of 0 is not the neutral choice it looks like: it asks to be
            // told the instant a *single* sample crosses the boundary, which is both
            // the hungriest setting and the one that reports every wobble of a fix
            // drifting near the edge. Given a window it can instead wait for the
            // crossing to still hold, so this is the platform's own half of what
            // GeofenceGate does in ours.
            //
            // Two minutes is a latency nobody automating "when I leave work" can
            // feel, and it is deliberately well short of the shortest away period
            // (one minute is clamped, but that countdown starts from the exit rather
            // than racing it).
            .setNotificationResponsiveness(GEOFENCE_RESPONSIVENESS_MS)
            .build()
        val request = GeofencingRequest.Builder()
            .addGeofence(geofence)
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .build()
        // Replace any existing geofence for this node, then add the new one —
        // *chained*, not fired side by side. Both act on the same PendingIntent
        // key, so a remove that resolves after the add deletes the fence the add
        // just registered, and the trigger then collects a bus that will never
        // speak again. The remove's own outcome is ignored on purpose: removing a
        // fence that was never registered is a no-op, and either way the add is
        // the part with an answer worth having.
        //
        // Neither blocks the trigger flow from collecting; the verdict reaches the
        // user through onResult instead, because before it did, a refusal was one
        // Logcat line under a macro that looked perfectly armed.
        geofencingClient.removeGeofences(pendingIntent)
            .continueWithTask { geofencingClient.addGeofences(request, pendingIntent) }
            .addOnSuccessListener {
                // Recorded only on success, so a refusal cannot leave a fingerprint
                // claiming a fence that was never registered — which would then be
                // believed for a day and leave the macro silently unwatched.
                registrationStore.record(nodeId.value, RegisteredFence(spec, now(), bootedAt()))
                onResult(GeofenceArmResult.Registered)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "addGeofences failed for node $nodeId: $e")
                val code = (e as? ApiException)?.statusCode ?: GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE
                onResult(GeofenceArmResult.Refused(code, refusalReason(code)))
            }
        return ScheduleHandle { releaseGeofence(nodeId, pendingIntent) }
    }

    /**
     * Takes the fence down and stops claiming it is there.
     *
     * One method for both ways out of [armGeofence], because the two must not
     * diverge: the skip path returns without having registered anything, but the
     * fence it declined to re-add is still very much registered, so its teardown has
     * exactly the same work to do.
     *
     * The order is deliberate. `forget` is a synchronous SharedPreferences write and
     * `removeGeofences` is a Task, so forgetting first means the worst case of a
     * remove that resolves late stays what it has always been — a fence briefly
     * missing until the next arm — rather than becoming a fingerprint that outlives
     * the fence it describes.
     */
    private fun releaseGeofence(nodeId: NodeId, pendingIntent: PendingIntent) {
        registrationStore.forget(nodeId.value)
        geofencingClient.removeGeofences(pendingIntent)
            .addOnFailureListener { e -> Log.w(TAG, "removeGeofences failed for node $nodeId: $e") }
    }

    override fun geofenceRegisteredAt(nodeId: NodeId): Long? =
        registrationStore.remembered(nodeId.value)?.registeredAtMillis

    private fun now(): Long = System.currentTimeMillis()

    /**
     * When the device booted, as a wall-clock instant.
     *
     * Wall clock minus uptime. Derived rather than observed because there is nothing
     * to observe: `BOOT_COMPLETED` may never be delivered on a restricted OEM build,
     * and Play Services drops every geofence across a reboot whether or not we were
     * told about it. See [GeofenceRegistration.SAME_BOOT_TOLERANCE_MS] for the drift
     * this carries.
     */
    private fun bootedAt(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    // An ordinary alarm rather than anything geofence-shaped, because Play
    // Services has no "outside for a while" transition to ask for. setWakeup is
    // the shared helper armAlarm and AndroidWaits already use, so this cannot
    // drift about which variant to use or how it degrades without
    // SCHEDULE_EXACT_ALARM — and for a countdown measured in tens of minutes,
    // an alarm batched a few minutes late is not a failure.
    override fun armGeofenceAway(nodeId: NodeId, awayDelayMs: Long) {
        val at = System.currentTimeMillis() + awayDelayMs.coerceAtLeast(0L)
        alarmManager.setWakeup(at, awayPendingIntent(nodeId), "geofence away for node $nodeId")
    }

    override fun cancelGeofenceAway(nodeId: NodeId) {
        alarmManager.cancel(awayPendingIntent(nodeId))
    }

    override fun geofencePresence(nodeId: NodeId): GeofencePresence = presenceStore.presence(nodeId.value)

    override fun recordGeofencePresence(nodeId: NodeId, presence: GeofencePresence) =
        presenceStore.record(nodeId.value, presence)

    /**
     * The alarm that says this node's place has been left for long enough.
     *
     * **Immutable, unlike [geofencePendingIntent] right below it.** That one has
     * to be mutable because Play Services writes the whole transition payload
     * into it at delivery time; this one carries everything it will ever carry —
     * the node id — so the usual rule applies again.
     *
     * The request code is the same `nodeId.hashCode()` the fence's uses, which is
     * safe rather than a collision: `PendingIntent` identity includes the intent's
     * action, and these two differ.
     */
    private fun awayPendingIntent(nodeId: NodeId): PendingIntent {
        val intent = Intent(appContext, GeofenceReceiver::class.java).apply {
            action = GeofenceReceiver.ACTION_GEOFENCE_AWAY
            putExtra(GeofenceReceiver.EXTRA_NODE_ID, nodeId.value)
        }
        val requestCode = nodeId.hashCode() and Int.MAX_VALUE
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(appContext, requestCode, intent, flags)
    }

    /**
     * Why a geofence cannot be registered right now, or null when it can.
     *
     * Both grants are needed and they are granted separately: `ACCESS_FINE_LOCATION`
     * comes from the ordinary runtime dialog, while `ACCESS_BACKGROUND_LOCATION` on
     * API 29+ cannot be prompted for in the same round and on API 30+ cannot be
     * prompted for at all — the user has to pick "Allow all the time" on the
     * Settings page. So the second one is missing far more often than the first, and
     * naming it is most of the value here.
     */
    private fun missingLocationPermission(): String? = when {
        !granted(Manifest.permission.ACCESS_FINE_LOCATION) ->
            "Ottomatic is not allowed to use your precise location. " +
                "Grant it on this node, or in Android settings under Location."

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ->
            "Ottomatic is only allowed to use your location while the app is open. " +
                "A geofence has to work when it is not, so set Location to " +
                "\"Allow all the time\" in Android settings → Apps → Ottomatic → Permissions → Location."

        else -> null
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * What a Play Services status code means to somebody holding the phone.
     *
     * `GeofenceStatusCodes.getStatusCodeString` answers with the constant's own
     * name, which is jargon at best and misdirection at worst — "DEVELOPER_ERROR"
     * names the developer, and a user reading it has no idea whether it is their
     * problem or ours.
     *
     * Which, for that code, it is: [missingLocationPermission] has already run by
     * the time anything reaches here, so a refusal at this point is not about
     * permissions. It means Play Services rejected the *request*, and the fix is
     * in this file. Saying so plainly is worth more than a settings tour that
     * cannot help — this exact code, blamed on permissions, is what sent the first
     * investigation of it down the wrong path.
     */
    private fun refusalReason(code: Int): String = when (code) {
        CommonStatusCodes.DEVELOPER_ERROR ->
            "Play Services rejected the geofence request itself. Location permissions are already " +
                "granted, so this is a bug in Ottomatic rather than a setting you can change — " +
                "please report it."

        GeofenceStatusCodes.GEOFENCE_INSUFFICIENT_LOCATION_PERMISSION ->
            "Ottomatic does not have permission to use your location in the background. " +
                "Set Location to \"Allow all the time\" in Android settings."

        GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE ->
            "Geofencing is unavailable. Switch Location on, and check that Google Location Accuracy " +
                "is enabled — geofences use network location rather than GPS."

        GeofenceStatusCodes.GEOFENCE_TOO_MANY_GEOFENCES ->
            "This device already has the maximum of 100 geofences registered."

        GeofenceStatusCodes.GEOFENCE_TOO_MANY_PENDING_INTENTS ->
            "Too many separate geofence registrations from Ottomatic."

        else -> GeofenceStatusCodes.getStatusCodeString(code)
    }

    override fun appLifecycleEvents(): Flow<com.example.ottomatic.core.trigger.TriggerEvent> =
        lifecycleBridge.events

    override fun variableChanges(name: String): Flow<com.example.ottomatic.core.trigger.TriggerEvent> =
        VariableStore.changesFor(name)

    /**
     * The broadcast Play Services sends when this node's fence is crossed.
     *
     * **Mutable, and it has to be.** `GeofencingClient.addGeofences` documents that
     * the PendingIntent passed to it must be mutable, because the whole payload —
     * which transition, which fences, the triggering location — is written into the
     * intent by Play Services at delivery time. That is exactly what an immutable
     * PendingIntent forbids, so a modern Play Services rejects the registration
     * outright with `DEVELOPER_ERROR`: the fence is never registered, the receiver
     * never fires, and the macro simply never runs.
     *
     * This is the opposite of the rule everywhere else in the app, and deliberately
     * so. The usual danger of a mutable PendingIntent is another app filling in an
     * unspecified target and having us send it somewhere of their choosing; this
     * intent names [GeofenceReceiver] by class, so there is no target to fill in.
     * `FLAG_MUTABLE` exists only from API 31 — below it a PendingIntent is mutable
     * unless `FLAG_IMMUTABLE` says otherwise, so omitting both is the same thing.
     */
    private fun geofencePendingIntent(nodeId: NodeId): PendingIntent {
        val intent = Intent(appContext, GeofenceReceiver::class.java).apply {
            action = GeofenceReceiver.ACTION_GEOFENCE_TRANSITION
        }
        val requestCode = nodeId.hashCode() and Int.MAX_VALUE
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(appContext, requestCode, intent, flags)
    }

    private fun GeofenceTransition.toGmsConstant(): Int = when (this) {
        GeofenceTransition.ENTER -> Geofence.GEOFENCE_TRANSITION_ENTER
        GeofenceTransition.EXIT -> Geofence.GEOFENCE_TRANSITION_EXIT
        GeofenceTransition.DWELL -> Geofence.GEOFENCE_TRANSITION_DWELL
    }

    private companion object {
        const val MIN_INTERVAL_MINUTES = 15L
        const val TAG = "Ottomatic"
    }
}
