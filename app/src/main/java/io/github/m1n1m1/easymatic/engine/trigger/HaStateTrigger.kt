package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.core.trigger.TriggerEvent
import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.domain.model.HomeAssistantRef
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Picker
import io.github.m1n1m1.easymatic.domain.model.config.PickerKind
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.HaStateChange
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.triggerNode
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
    @Label("What starts it")
    @Picker(PickerKind.HA_TRIGGER, scopedBy = ["entity"], optional = true)
    val trigger: String = "",
    @Label("Held for (seconds)")
    val forSeconds: Int = 0,
)

/**
 * Starts a macro on one of Home Assistant's own triggers for an entity.
 *
 * The trigger half of the pair CLAUDE.md's rule asks for, with `value.ha_state` as the
 * other: this answers *"tell me when it changes"* and that answers *"what is it right
 * now?"*. Both are needed and neither substitutes for the other — "when the door opens,
 * turn the hall light on" is this node, and "when I get home, *if* the door is open, say
 * so" is a value read inside an `action.if`.
 *
 * **The node used to watch `state_changed` and filter it here, and that was the wrong shape
 * rather than an incomplete one.** It offered *changes to*, *changes from* and "also when only
 * its details change", which are the fields somebody designs who has read `/api/states` and
 * assumed a state machine underneath. Home Assistant does not work that way: what it offers
 * for a media player is `started_playing`, `paused_playing`, `muted` and
 * `volume_crossed_threshold` — a list you cannot derive from a state list because half of it
 * is not about state at all, and the half that is carries evaluation rules (`for`, `behavior`)
 * a client would have to reproduce exactly to agree with what the user already saw in the web
 * interface. So the trigger is **subscribed to** rather than re-implemented, over
 * `subscribe_trigger`, and the answer is the same answer by construction.
 *
 * **Blank means "whenever it changes"**, which is what makes the reshape safe for a macro
 * saved before it: `entity` keeps its key and its meaning, the two filter fields go away, and
 * a node that had them falls back to the unfiltered watch it would have had with both blank.
 * That row is also the honest last resort — an entity no integration declares triggers for,
 * or an instance too old for the trigger platform, would otherwise leave the picker empty and
 * the node unconfigurable — and it is serviced by the `state_changed` stream the state cache
 * already needs, so it costs no second subscription.
 *
 * **`forSeconds` is sent only when it is set**, and deliberately not defaulted to something
 * tidier. A trigger that does not declare a `for` option **refuses the whole subscription**
 * rather than ignoring it, so a value nobody asked for would silently stop the node firing —
 * which is exactly the failure this node is being rewritten to escape. Zero sends nothing, and
 * a refusal is reported into the macro's own console naming the trigger.
 *
 * The entity is what decides the *subscription*, and unlike the old filters it is not
 * something that can be re-read per event — so an edit to either field re-arms, which the
 * editor already does through `Workflow.runtimeSignature()`.
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
            spec = HaWatchSpec.StateWatch(
                hubId = reference.hubId,
                entityId = reference.id,
                trigger = config.trigger.trim(),
                options = triggerOptions(config),
            ),
            onReport = { message, level -> host.report(node, message, level) },
        )
        try {
            host.busEventsFor(node.id)
                .filter { it.source == TriggerSource.HOME_ASSISTANT && it.triggerNodeId == node.id }
                // A named trigger's event carries the entity it was subscribed for, so this
                // only ever excludes the built-in watch's stream — which is shared with the
                // state cache and reaches every node interested in the hub.
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
 * The options object sent with the subscription, as JSON.
 *
 * **Empty unless something was actually set.** Home Assistant validates a trigger's options
 * against what that trigger declares and refuses the subscription outright on anything it does
 * not recognise, so sending `{"for": 0}` to a trigger with no `for` option would stop the node
 * firing at all — a worse failure than the one the field exists to solve, and a silent one.
 *
 * `for` is written in Home Assistant's own `HH:MM:SS`, which is what its automation editor
 * stores and what the trigger's schema accepts; a bare number is rejected by several of them.
 */
internal fun triggerOptions(config: HaStateTriggerConfig): String {
    val seconds = config.forSeconds.coerceAtLeast(0)
    if (seconds == 0 || config.trigger.isBlank()) return ""
    val hours = seconds / SECONDS_PER_HOUR
    val minutes = (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    return """{"for":"%02d:%02d:%02d"}""".format(hours, minutes, seconds % SECONDS_PER_MINUTE)
}

private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3600

/**
 * Whether this change is one the node was asked about.
 *
 * Only the **built-in** watch filters at all: a named trigger was evaluated by Home Assistant,
 * which already applied every rule the user set, and second-guessing it here is precisely what
 * this node stopped doing. What remains is the one thing the raw stream cannot express — that
 * `state_changed` also fires when only an entity's attributes moved, a light's `brightness`
 * ticking while `state` stays `"on"` — which without this filter makes "whenever it changes"
 * fire dozens of times a minute on a dimmer.
 *
 * File-level and internal so it is testable without a host or a bus, on
 * `SmsTriggerFilterTest`'s shape.
 */
internal fun matches(config: HaStateTriggerConfig, change: HaStateChange): Boolean =
    config.trigger.isNotBlank() || change.state != change.previousState

internal fun TriggerEvent.toHaStateChange(): HaStateChange = HaStateChange(
    entityId = payload[HaPayload.ENTITY_ID].orEmpty(),
    name = payload[HaPayload.FRIENDLY_NAME].orEmpty(),
    state = payload[HaPayload.STATE].orEmpty(),
    previousState = payload[HaPayload.PREVIOUS_STATE].orEmpty(),
    unit = payload[HaPayload.UNIT].orEmpty(),
    attributes = payload[HaPayload.ATTRIBUTES].orEmpty(),
    changedAt = timestamp,
)
