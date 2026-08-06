package com.example.ottomatic.data.trigger

import com.example.ottomatic.core.model.NodeId
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.ottomatic.core.trigger.TriggerBus
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.engine.service.MacroEngineService
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
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
 *
 * Waking the app is not the same as waking the *engine*, which is the distinction
 * this used to miss: the process came up, the event went onto a `replay = 0` bus
 * with nothing subscribed, and it was gone. So both halves happen here — the event
 * is parked for its node, and the engine is asked to arm.
 */
class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val transitions = collectTransitions(intent)
        if (transitions.isEmpty()) return
        for (each in transitions) {
            // Held rather than emitted, and held *before* the service is asked to
            // start: this broadcast may well be what spawned the process, in which
            // case nothing is subscribed yet and a plain emit is discarded on the
            // spot. startForegroundService can reach onCreate synchronously on some
            // paths, so parking the event first is what makes the order not matter.
            TriggerBus.emitOrHold(
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
        // Nothing else on this path starts the engine. `Application.onCreate`
        // launches a re-arm behind a disk read, asynchronously, so it has not
        // happened by the time this method returns — the fence fired, the receiver
        // ran, and nobody was listening.
        //
        // Legal from here: "an event that's related to geofencing" is one of the
        // documented exemptions from the Android 12+ background
        // foreground-service-start restriction. This is the route BootReceiver
        // already takes for BOOT_COMPLETED, guard included.
        runCatching { MacroEngineService.start(context, MacroEngineService.ACTION_REARM_ALL) }
            .onFailure { Log.w(TAG, "Could not start the engine for a geofence transition", it) }
    }

    /**
     * Everything here reports to Logcat and nowhere else.
     *
     * A receiver lives in `data/`, has no [com.example.ottomatic.engine.ExecutionContext],
     * and could only find out which workflow a node belongs to by reading every
     * file in `{filesDir}/workflows/` — on the main thread, inside `onReceive`.
     * So the in-app half of this story is told by `GeofenceTrigger` at arm time,
     * where the attribution already exists, and this half is told to
     * `adb logcat -s Ottomatic`.
     */
    // Four guards and the result. Folding them back into one `?.takeIf { }?.let { }`
    // chain is precisely what swallowed the GMS error code, so the returns stay.
    @Suppress("ReturnCount")
    private fun collectTransitions(intent: Intent): List<Transition> {
        if (intent.action != ACTION_GEOFENCE_TRANSITION) return emptyList()
        val event = GeofencingEvent.fromIntent(intent) ?: return emptyList()
        if (event.hasError()) {
            // Swallowing this is what hid "location is switched off" and "too many
            // geofences registered" behind a macro that simply never ran.
            val code = event.errorCode
            Log.w(TAG, "Geofence broadcast carried an error: ${GeofenceStatusCodes.getStatusCodeString(code)}")
            return emptyList()
        }
        val name = transitionNameFor(event.geofenceTransition)
        if (name == null) {
            Log.w(TAG, "Unhandled geofence transition type ${event.geofenceTransition}")
            return emptyList()
        }
        val location = event.triggeringLocation
        return event.triggeringGeofences.orEmpty().map { geofence ->
            Log.i(TAG, "Geofence $name for node ${geofence.requestId}")
            Transition(
                nodeId = NodeId(geofence.requestId),
                transition = name,
                latitude = location?.latitude,
                longitude = location?.longitude,
                accuracy = location?.accuracy,
            )
        }
    }

    private fun transitionNameFor(gmsTransition: Int): String? = when (gmsTransition) {
        Geofence.GEOFENCE_TRANSITION_ENTER -> EVENT_ENTER
        Geofence.GEOFENCE_TRANSITION_EXIT -> EVENT_EXIT
        Geofence.GEOFENCE_TRANSITION_DWELL -> EVENT_DWELL
        else -> null
    }

    private data class Transition(
        val nodeId: NodeId,
        val transition: String,
        val latitude: Double?,
        val longitude: Double?,
        val accuracy: Float?,
    )

    companion object {
        private const val TAG = "Ottomatic"

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
