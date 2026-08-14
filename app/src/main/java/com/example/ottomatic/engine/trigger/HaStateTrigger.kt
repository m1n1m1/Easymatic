package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.HomeAssistantRef
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Picker
import com.example.ottomatic.domain.model.config.PickerKind
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.HaStateChange
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

@Serializable
data class HaStateTriggerConfig(
    @Label("Entity")
    @Picker(PickerKind.HA_ENTITY)
    val entity: String = "",
    @Label("Changes to")
    val toState: String = "",
    @Label("Changes from")
    val fromState: String = "",
    @Label("Also when only its details change")
    val includeAttributeChanges: Boolean = false,
)

/**
 * Starts a macro when a Home Assistant entity changes state.
 *
 * The trigger half of the pair CLAUDE.md's rule asks for, with `value.ha_state` as the
 * other: this answers *"tell me when it changes"* and that answers *"what is it right
 * now?"*. Both are needed and neither substitutes for the other — "when the door opens,
 * turn the hall light on" is this node, and "when I get home, *if* the door is open, say
 * so" is a value read inside an `action.if`.
 *
 * **`includeAttributeChanges` defaults to false, and that default is the whole
 * usability of the node.** Home Assistant fires `state_changed` for attribute-only
 * changes as well as real transitions — a light's `brightness` ticking while `state`
 * stays `"on"`, a media player's position advancing every few seconds, a thermometer
 * reporting the same reading with a new timestamp. Without this filter, "when the porch
 * light comes on" fires every time anything about that light moves, which on a
 * dimmer-controlled lamp is dozens of times a minute. With it, the node means what its
 * name says.
 *
 * The filters are applied **per event** rather than at arm time, on `MailTrigger`'s
 * reasoning: editing "changes to" takes effect with no re-arm signal to invent. Only the
 * entity itself goes into the spec, because that is what decides the routing.
 */
class HaStateTrigger : Trigger<HaStateTriggerConfig, HaStateChange> {

    override val definition = triggerNode<HaStateTriggerConfig, HaStateChange>(
        typeId = "trigger.ha_state",
        displayName = "Home Assistant Entity Changed",
        description = "Starts when a Home Assistant entity changes — a sensor, a switch, a door, a thermostat",
        category = NodeCategory.SMART_HOME_EVENTS,
        icon = NodeIcon.HOME,
        output = dataOut<HaStateChange>("change", label = "Change"),
    )

    override fun activate(
        config: HaStateTriggerConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<HaStateChange>> = flow {
        val reference = HomeAssistantRef.parse(config.entity)
        if (reference == null || reference.id.isBlank()) {
            // Stays unarmed rather than watching nothing, and says so where the person
            // who placed the node will see it.
            host.report(node, "No Home Assistant entity chosen, so this trigger cannot fire", LogLevel.WARN)
            return@flow
        }
        if (host.smartHomeHub(reference.hubId) == null) {
            host.report(node, "The Home Assistant hub this watches has been removed", LogLevel.WARN)
            return@flow
        }
        host.report(node, "Watching ${reference.name.ifBlank { reference.id }}")
        val handle = host.armHomeAssistantWatch(
            nodeId = node.id,
            spec = HaWatchSpec.StateWatch(reference.hubId, reference.id),
            onReport = { message, level -> host.report(node, message, level) },
        )
        try {
            host.busEventsFor(node.id)
                .filter { it.source == TriggerSource.HOME_ASSISTANT && it.triggerNodeId == node.id }
                .filter { it.payload[HaPayload.ENTITY_ID] == reference.id }
                .map { it.toHaStateChange() }
                .filter { matches(config, it) }
                .collect { emit(NodeOutput(it)) }
        } finally {
            handle.cancel()
        }
    }
}

/**
 * Whether this change is one the node was asked about.
 *
 * File-level and internal so it is testable without a host or a bus, on
 * `SmsTriggerFilterTest`'s shape.
 */
@Suppress("ReturnCount") // One early exit per filter; a single boolean would hide which is which.
internal fun matches(config: HaStateTriggerConfig, change: HaStateChange): Boolean {
    // The default, and the reason the node is usable: an attribute-only change is not
    // what "when the porch light comes on" means.
    if (!config.includeAttributeChanges && change.state == change.previousState) return false
    if (config.toState.isNotBlank() && !change.state.equals(config.toState.trim(), ignoreCase = true)) return false
    if (config.fromState.isNotBlank() &&
        !change.previousState.equals(config.fromState.trim(), ignoreCase = true)
    ) {
        return false
    }
    return true
}

internal fun TriggerEvent.toHaStateChange(): HaStateChange = HaStateChange(
    entityId = payload[HaPayload.ENTITY_ID].orEmpty(),
    name = payload[HaPayload.FRIENDLY_NAME].orEmpty(),
    state = payload[HaPayload.STATE].orEmpty(),
    previousState = payload[HaPayload.PREVIOUS_STATE].orEmpty(),
    unit = payload[HaPayload.UNIT].orEmpty(),
    attributes = payload[HaPayload.ATTRIBUTES].orEmpty(),
    changedAt = timestamp,
)
