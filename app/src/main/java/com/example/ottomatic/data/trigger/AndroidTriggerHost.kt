package com.example.ottomatic.data.trigger

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.annotation.SuppressLint
import android.os.Build
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.data.GeofencePlaceRepository
import com.example.ottomatic.domain.model.GeofencePlace
import com.example.ottomatic.engine.trigger.BatteryDirection
import com.example.ottomatic.engine.trigger.GeofenceTransition
import com.example.ottomatic.engine.trigger.ScheduleHandle
import com.example.ottomatic.engine.trigger.TriggerHost
import com.google.android.gms.location.Geofence
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
) : TriggerHost {

    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val geofencingClient = LocationServices.getGeofencingClient(appContext)
    private val lifecycleBridge = AppLifecycleBridge(appContext)

    @Suppress("UnusedPrivateProperty") // Kept alive so its receiver stays registered.
    private val screenBridge = ScreenBroadcastBridge(appContext)

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
        // setExact* needs SCHEDULE_EXACT_ALARM from API 31; without it the call
        // throws, so fall back to the inexact variant rather than failing the
        // whole trigger. Both variants fire through doze.
        if (canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMs, pendingIntent)
        } else {
            Log.w(TAG, "Exact alarms not permitted; node $nodeId falls back to an inexact alarm")
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMs, pendingIntent)
        }
        return ScheduleHandle { alarmManager.cancel(pendingIntent) }
    }

    private fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

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

    override fun geofencePlace(id: String): GeofencePlace? = geofencePlaces.get(id)

    @SuppressLint("MissingPermission")
    override fun armGeofence(
        nodeId: NodeId,
        latitude: Double,
        longitude: Double,
        radiusMeters: Float,
        transitions: Set<GeofenceTransition>,
        dwellDelayMs: Int,
    ): ScheduleHandle {
        val transitionTypes = transitions.fold(0) { acc, t -> acc or t.toGmsConstant() }
        val geofence = Geofence.Builder()
            .setRequestId(nodeId.value)
            .setCircularRegion(latitude, longitude, radiusMeters)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(transitionTypes)
            .setLoiteringDelay(dwellDelayMs)
            .build()
        val request = GeofencingRequest.Builder()
            .addGeofence(geofence)
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .build()
        val pendingIntent = geofencePendingIntent(nodeId)
        // Replace any existing geofence for this node, then add the new one.
        // removeGeofences is fire-and-forget; addGeofences is too — failures
        // are logged but do not block the trigger flow from collecting.
        geofencingClient.removeGeofences(pendingIntent)
        geofencingClient.addGeofences(request, pendingIntent)
            .addOnFailureListener { e -> Log.w(TAG, "addGeofences failed for node $nodeId: $e") }
        return ScheduleHandle {
            geofencingClient.removeGeofences(pendingIntent)
                .addOnFailureListener { e -> Log.w(TAG, "removeGeofences failed for node $nodeId: $e") }
        }
    }

    override fun appLifecycleEvents(): Flow<com.example.ottomatic.core.trigger.TriggerEvent> =
        lifecycleBridge.events

    override fun variableChanges(name: String): Flow<com.example.ottomatic.core.trigger.TriggerEvent> =
        VariableStore.changesFor(name)

    private fun geofencePendingIntent(nodeId: NodeId): PendingIntent {
        val intent = Intent(appContext, GeofenceReceiver::class.java).apply {
            action = GeofenceReceiver.ACTION_GEOFENCE_TRANSITION
        }
        val requestCode = nodeId.hashCode() and Int.MAX_VALUE
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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
