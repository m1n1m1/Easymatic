package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.GeofenceEvent
import com.example.ottomatic.domain.model.schema.Item
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow

/**
 * Trigger for `trigger.geofence`. Arms a platform geofence via the host when
 * collection starts, surfaces matching bus events, and cancels the geofence
 * when the flow is cancelled.
 *
 * Produces a typed [GeofenceEvent] item on the `event` data port.
 *
 * Payload contract with `GeofenceReceiver` in `data/` (string keys):
 * - `event` ∈ `"enter"`, `"exit"`, `"dwell"`
 * - `lat`, `lng`, `accuracy`, `timestamp`
 *
 * Config keys:
 * - `latitude` (DOUBLE) — geofence centre latitude.
 * - `longitude` (DOUBLE) — geofence centre longitude.
 * - `radiusMeters` (INT) — geofence radius in metres (default 100).
 * - `event` (ENUM) — comma-separated subset of `enter,exit,dwell` (default `enter`).
 * - `dwellDelayMs` (INT, optional) — loitering delay in milliseconds.
 */
class GeofenceTrigger : Trigger {

    override val typeId: String = TYPE_ID

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<TriggerEvent> {
        val config = parseConfig(node) ?: return emptyFlow()
        return flow {
            val handle = host.armGeofence(
                nodeId = node.id,
                latitude = config.latitude,
                longitude = config.longitude,
                radiusMeters = config.radiusMeters,
                transitions = config.transitions,
                dwellDelayMs = config.dwellDelayMs,
            )
            try {
                host.busEvents()
                    .filter { it.source == TriggerSource.GEOFENCE && it.triggerNodeId == node.id }
                    .filter { event -> event.payload[KEY_EVENT] in config.transitionNames }
                    .collect { bus ->
                        emit(
                            TriggerEvent(
                                triggerNodeId = node.id,
                                dataOut = mapOf(
                                    "event" to Item.of(
                                        GeofenceEvent(
                                            triggerNodeId = node.id,
                                            transition = bus.payload[KEY_EVENT].orEmpty(),
                                            latitude = bus.payload[KEY_LATITUDE]?.toDoubleOrNull()
                                                ?: config.latitude,
                                            longitude = bus.payload[KEY_LONGITUDE]?.toDoubleOrNull()
                                                ?: config.longitude,
                                            accuracyMeters = bus.payload[KEY_ACCURACY]?.toFloatOrNull() ?: 0f,
                                            timestamp = bus.payload[KEY_TIMESTAMP]?.toLongOrNull()
                                                ?: bus.firedAtEpochMs,
                                        ),
                                    ),
                                ),
                            ),
                        )
                    }
            } finally {
                handle.cancel()
            }
        }
    }

    private fun parseConfig(node: WorkflowNode): GeofenceConfig? {
        val latitude = node.config[CONFIG_LATITUDE]?.toDoubleOrNull()
        val longitude = node.config[CONFIG_LONGITUDE]?.toDoubleOrNull()
        if (latitude == null || longitude == null) return null
        val radiusMeters = node.config[CONFIG_RADIUS_METERS]?.toFloatOrNull() ?: DEFAULT_RADIUS_METERS
        val transitions = GeofenceTransition.parse(node.config[CONFIG_EVENT])
        return GeofenceConfig(
            latitude = latitude,
            longitude = longitude,
            radiusMeters = radiusMeters,
            transitions = transitions,
            transitionNames = transitions.map { it.name.lowercase() }.toSet(),
            dwellDelayMs = node.config[CONFIG_DWELL_DELAY_MS]?.toIntOrNull() ?: DEFAULT_DWELL_DELAY_MS,
        )
    }

    private data class GeofenceConfig(
        val latitude: Double,
        val longitude: Double,
        val radiusMeters: Float,
        val transitions: Set<GeofenceTransition>,
        val transitionNames: Set<String>,
        val dwellDelayMs: Int,
    )

    companion object {
        const val TYPE_ID = "trigger.geofence"

        const val CONFIG_LATITUDE = "latitude"
        const val CONFIG_LONGITUDE = "longitude"
        const val CONFIG_RADIUS_METERS = "radiusMeters"
        const val CONFIG_EVENT = "event"
        const val CONFIG_DWELL_DELAY_MS = "dwellDelayMs"

        const val KEY_EVENT = "event"
        const val KEY_LATITUDE = "lat"
        const val KEY_LONGITUDE = "lng"
        const val KEY_ACCURACY = "accuracy"
        const val KEY_TIMESTAMP = "timestamp"

        const val DEFAULT_RADIUS_METERS = 100f
    }
}
