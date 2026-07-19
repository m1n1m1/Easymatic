package com.example.ottomatic.data.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.ottomatic.core.trigger.TriggerBus
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.core.trigger.TriggerSource
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

/**
 * Receives geofence transition broadcasts from Play Services and pushes a
 * [TriggerEvent] onto the [TriggerBus] for each triggering geofence.
 *
 * The geofence `requestId` carries the workflow node id (set by
 * [AndroidTriggerHost.armGeofence]), so each event is addressed to the exact
 * trigger node that armed it — no sentinel/fan-out needed.
 *
 * Manifest-registered for [ACTION_GEOFENCE_TRANSITION] so it wakes a killed app.
 * The [PendingIntent] built by `AndroidTriggerHost` targets this action.
 */
class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val transitions = collectTransitions(intent)
        if (transitions.isEmpty()) return
        for (each in transitions) {
            TriggerBus.emit(
                TriggerEvent(
                    source = TriggerSource.GEOFENCE,
                    triggerNodeId = each.nodeId,
                    payload = buildMap {
                        put(KEY_EVENT, each.transition)
                        each.latitude?.let { put(KEY_LATITUDE, it.toString()) }
                        each.longitude?.let { put(KEY_LONGITUDE, it.toString()) }
                        each.accuracy?.let { put(KEY_ACCURACY, it.toString()) }
                        put(KEY_TIMESTAMP, System.currentTimeMillis().toString())
                    },
                ),
            )
        }
    }

    private fun collectTransitions(intent: Intent): List<Transition> {
        if (intent.action != ACTION_GEOFENCE_TRANSITION) return emptyList()
        return GeofencingEvent.fromIntent(intent)
            ?.takeIf { !it.hasError() }
            ?.let { event ->
                val name = transitionNameFor(event.geofenceTransition) ?: return@let null
                val location = event.triggeringLocation
                event.triggeringGeofences.orEmpty().map { geofence ->
                    Transition(
                        nodeId = geofence.requestId,
                        transition = name,
                        latitude = location?.latitude,
                        longitude = location?.longitude,
                        accuracy = location?.accuracy,
                    )
                }
            }
            ?: emptyList()
    }

    private fun transitionNameFor(gmsTransition: Int): String? = when (gmsTransition) {
        Geofence.GEOFENCE_TRANSITION_ENTER -> EVENT_ENTER
        Geofence.GEOFENCE_TRANSITION_EXIT -> EVENT_EXIT
        Geofence.GEOFENCE_TRANSITION_DWELL -> EVENT_DWELL
        else -> null
    }

    private data class Transition(
        val nodeId: String,
        val transition: String,
        val latitude: Double?,
        val longitude: Double?,
        val accuracy: Float?,
    )

    companion object {
        const val ACTION_GEOFENCE_TRANSITION = "com.example.ottomatic.GEOFENCE_TRANSITION"

        const val EVENT_ENTER = "enter"
        const val EVENT_EXIT = "exit"
        const val EVENT_DWELL = "dwell"

        const val KEY_EVENT = "event"
        const val KEY_LATITUDE = "lat"
        const val KEY_LONGITUDE = "lng"
        const val KEY_ACCURACY = "accuracy"
        const val KEY_TIMESTAMP = "timestamp"
    }
}
