package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.permissions.PermissionRequirement
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Picker
import com.example.ottomatic.domain.model.config.PickerKind
import com.example.ottomatic.domain.model.config.VisibleWhen
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
 * [placeId] references a [com.example.ottomatic.domain.model.GeofencePlace] in
 * the shared library rather than carrying coordinates of its own: the same
 * place is usually watched by several macros, and one edit should move all of
 * them. A blank id (or one whose place has been deleted) means "unconfigured",
 * and the trigger is not armed — the same failure mode the old null coordinates
 * had.
 *
 * The radius belongs to the place, not here. The armed transitions are three
 * independent switches rather than a comma-joined enum string, which could
 * express combinations the UI never offered (and vice versa).
 */
@Serializable
data class GeofenceConfig(
    @Label("Place") @Picker(PickerKind.GEOFENCE_PLACE) val placeId: String = "",
    @Label("On enter") val onEnter: Boolean = true,
    @Label("On exit") val onExit: Boolean = false,
    @Label("On dwell") val onDwell: Boolean = false,
    @Label("Dwell delay (ms)")
    @VisibleWhen("onDwell", "true")
    val dwellDelayMs: Int = DEFAULT_DWELL_DELAY_MS,
) {
    /** The armed transitions; always at least [GeofenceTransition.ENTER]. */
    val transitions: Set<GeofenceTransition>
        get() = buildSet {
            if (onEnter) add(GeofenceTransition.ENTER)
            if (onExit) add(GeofenceTransition.EXIT)
            if (onDwell) add(GeofenceTransition.DWELL)
        }.ifEmpty { setOf(GeofenceTransition.ENTER) }
}

/**
 * Trigger for `trigger.geofence`. Resolves its configured place through the
 * host, arms a platform geofence at that place's centre and radius when
 * collection starts, surfaces matching bus events, and cancels the geofence
 * when the flow is cancelled.
 *
 * The place is read once, at activation. Editing a place therefore only takes
 * effect on the next arm, which is why the editor asks the engine service to
 * re-arm after a save.
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
        // Foreground location gets the fence registered at all; background
        // location is what lets it keep firing once the app is off-screen,
        // which is the only way a geofence macro is ever useful.
        permissions = listOf(
            PermissionRequirement(
                manifestPermission = Permissions.ACCESS_FINE_LOCATION.manifest,
                type = PrerequisiteType.RUNTIME,
                rationaleKey = "geofence.location",
            ),
            PermissionRequirement(
                manifestPermission = Permissions.ACCESS_BACKGROUND_LOCATION.manifest,
                type = PrerequisiteType.RUNTIME,
                rationaleKey = "geofence.backgroundLocation",
            ),
        ),
    )

    override fun activate(
        config: GeofenceConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<GeofenceEvent>> {
        // No place chosen, or the chosen one has since been deleted: nothing to
        // arm. Staying silent beats arming a fence at (0, 0).
        val place = config.placeId.takeIf { it.isNotBlank() }?.let(host::geofencePlace) ?: return emptyFlow()
        val latitude = place.latitude
        val longitude = place.longitude
        val transitions = config.transitions
        val armedNames = transitions.mapTo(mutableSetOf()) { it.payloadValue }
        return flow {
            val handle = host.armGeofence(
                nodeId = node.id,
                latitude = latitude,
                longitude = longitude,
                radiusMeters = place.radiusMeters,
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
