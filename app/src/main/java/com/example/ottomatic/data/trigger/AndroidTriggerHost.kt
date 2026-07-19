package com.example.ottomatic.data.trigger

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.annotation.SuppressLint
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.ottomatic.engine.trigger.GeofenceTransition
import com.example.ottomatic.engine.trigger.ScheduleHandle
import com.example.ottomatic.engine.trigger.TriggerHost
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import java.util.concurrent.TimeUnit

/**
 * Android implementation of [TriggerHost]. Supplies real system streams and
 * arms [ScheduleWorker] via WorkManager, and geofences via Play Services.
 */
class AndroidTriggerHost(
    context: Context,
) : TriggerHost {

    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val geofencingClient = LocationServices.getGeofencingClient(appContext)

    override fun armSchedule(
        nodeId: String,
        intervalMinutes: Long,
        cron: String?,
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

    override fun armBatteryLevelPoll(
        nodeId: String,
        intervalMinutes: Long,
        direction: String,
        threshold: Int,
    ): ScheduleHandle {
        val minutes = intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES)
        val request = PeriodicWorkRequestBuilder<BatteryLevelWorker>(minutes, TimeUnit.MINUTES)
            .setInputData(
                workDataOf(
                    BatteryLevelWorker.KEY_NODE_ID to nodeId,
                    BatteryLevelWorker.KEY_DIRECTION to direction,
                    BatteryLevelWorker.KEY_LEVEL to threshold,
                ),
            )
            .build()
        val workName = BatteryLevelWorker.WORK_NAME_PREFIX + nodeId
        workManager.enqueueUniquePeriodicWork(workName, ExistingPeriodicWorkPolicy.UPDATE, request)
        return ScheduleHandle { workManager.cancelUniqueWork(workName) }
    }

    @SuppressLint("MissingPermission")
    override fun armGeofence(
        nodeId: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Float,
        transitions: Set<GeofenceTransition>,
        dwellDelayMs: Int,
    ): ScheduleHandle {
        val transitionTypes = transitions.fold(0) { acc, t -> acc or t.toGmsConstant() }
        val geofence = Geofence.Builder()
            .setRequestId(nodeId)
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

    private fun geofencePendingIntent(nodeId: String): PendingIntent {
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
