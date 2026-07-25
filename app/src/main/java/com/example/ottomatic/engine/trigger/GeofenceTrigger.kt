package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.GeofenceEvent
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable

/**
 * Config for `trigger.geofence`.
 *
 * [latitude] and [longitude] are nullable with no default: an unconfigured
 * geofence has no centre and is not armed. The armed transitions are three
 * independent switches rather than the previous comma-joined enum string, which
 * could express combinations the UI never offered (and vice versa).
 */
@Serializable
data class GeofenceConfig(
    @Label("Latitude") val latitude: Double? = null,
    @Label("Longitude") val longitude: Double? = null,
    @Label("Radius (metres)") val radiusMeters: Int = DEFAULT_RADIUS_METERS,
    @Label("On enter") val onEnter: Boolean = true,
    @Label("On exit") val onExit: Boolean = false,
    @Label("On dwell") val onDwell: Boolean = false,
    @Label("Dwell delay (ms, only when dwell is armed)") val dwellDelayMs: Int = DEFAULT_DWELL_DELAY_MS,
) {
    /** The armed transitions; always at least [GeofenceTransition.ENTER]. */
    val transitions: Set<GeofenceTransition>
        get() = buildSet {
            if (onEnter) add(GeofenceTransition.ENTER)
            if (onExit) add(GeofenceTransition.EXIT)
            if (onDwell) add(GeofenceTransition.DWELL)
        }.ifEmpty { setOf(GeofenceTransition.ENTER) }
}

private const val DEFAULT_RADIUS_METERS = 100

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
 */
class GeofenceTrigger : Trigger<GeofenceConfig, GeofenceEvent> {

    override val definition = triggerNode<GeofenceConfig, GeofenceEvent>(
        typeId = TYPE_ID.value,
        displayName = "Geofence",
        description = "Starts when the device enters, exits or dwells inside a circular area",
        category = NodeCategory.LOCATION,
        icon = NodeIcon.LOCATION,
        output = dataOut<GeofenceEvent>("event", label = "Event"),
    )

    override fun activate(
        config: GeofenceConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<GeofenceEvent>> {
        val latitude = config.latitude
        val longitude = config.longitude
        if (latitude == null || longitude == null) return emptyFlow()
        val transitions = config.transitions
        val armedNames = transitions.mapTo(mutableSetOf()) { it.payloadValue }
        return flow {
            val handle = host.armGeofence(
                nodeId = node.id,
                latitude = latitude,
                longitude = longitude,
                radiusMeters = config.radiusMeters.toFloat(),
                transitions = transitions,
                dwellDelayMs = config.dwellDelayMs,
            )
            try {
                host.busEvents()
                    .filter { it.source == TriggerSource.GEOFENCE && it.triggerNodeId == node.id }
                    .filter { it.payload[KEY_EVENT] in armedNames }
                    .collect { bus ->
                        emit(
                            NodeOutput(
                                GeofenceEvent(
                                    triggerNodeId = node.id.value,
                                    transition = bus.payload[KEY_EVENT].orEmpty(),
                                    latitude = bus.payload[KEY_LATITUDE]?.toDoubleOrNull() ?: latitude,
                                    longitude = bus.payload[KEY_LONGITUDE]?.toDoubleOrNull() ?: longitude,
                                    accuracyMeters = bus.payload[KEY_ACCURACY]?.toFloatOrNull() ?: 0f,
                                    timestamp = bus.payload[KEY_TIMESTAMP]?.toLongOrNull() ?: bus.firedAtEpochMs,
                                ),
                            ),
                        )
                    }
            } finally {
                handle.cancel()
            }
        }
    }

    companion object {
        val TYPE_ID = NodeTypeId("trigger.geofence")

        const val KEY_LATITUDE = "lat"
        const val KEY_LONGITUDE = "lng"
        const val KEY_ACCURACY = "accuracy"
    }
}
