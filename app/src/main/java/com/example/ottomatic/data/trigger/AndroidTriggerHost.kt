package com.example.ottomatic.data.trigger

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.annotation.SuppressLint
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.service.Contacts
import com.example.ottomatic.core.trigger.TriggerBus
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.data.GeofencePlaceRepository
import com.example.ottomatic.data.MailAccountRepository
import com.example.ottomatic.data.NfcTagRepository
import com.example.ottomatic.data.mail.MailWatchers
import com.example.ottomatic.data.nfc.NfcReader
import com.example.ottomatic.data.sensor.SensorBridge
import com.example.ottomatic.data.service.AndroidContacts
import com.example.ottomatic.domain.model.GeofencePlace
import com.example.ottomatic.domain.model.MailAccount
import com.example.ottomatic.domain.model.NfcTag
import com.example.ottomatic.engine.trigger.BatteryDirection
import com.example.ottomatic.engine.trigger.GeofenceArmResult
import com.example.ottomatic.engine.trigger.GeofenceTransition
import com.example.ottomatic.engine.trigger.MailWatchSpec
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
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit

/**
 * Android implementation of [TriggerHost]. Supplies real system streams and
 * arms [ScheduleWorker] via WorkManager, and geofences via Play Services.
 *
 * [geofencePlaces] is the shared place library the geofence trigger resolves
 * its configured place against; it is read synchronously from the repository's
 * in-memory cache, because arming happens outside a suspending context.
 */
@Suppress("TooManyFunctions") // One override per TriggerHost capability; the interface sets the count.
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
) : TriggerHost {

    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val geofencingClient = LocationServices.getGeofencingClient(appContext)
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

    override fun mailAccount(id: String): MailAccount? = mailAccounts?.get(id)

    override fun armMailWatch(
        nodeId: NodeId,
        accountId: String,
        spec: MailWatchSpec,
        onReport: (String, LogLevel) -> Unit,
    ): ScheduleHandle = mailWatchers.arm(nodeId, accountId, spec, onReport)

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
            .build()
        val request = GeofencingRequest.Builder()
            .addGeofence(geofence)
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .build()
        val pendingIntent = geofencePendingIntent(nodeId)
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
            .addOnSuccessListener { onResult(GeofenceArmResult.Registered) }
            .addOnFailureListener { e ->
                Log.w(TAG, "addGeofences failed for node $nodeId: $e")
                val code = (e as? ApiException)?.statusCode ?: GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE
                onResult(GeofenceArmResult.Refused(code, refusalReason(code)))
            }
        return ScheduleHandle {
            geofencingClient.removeGeofences(pendingIntent)
                .addOnFailureListener { e -> Log.w(TAG, "removeGeofences failed for node $nodeId: $e") }
        }
    }

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
