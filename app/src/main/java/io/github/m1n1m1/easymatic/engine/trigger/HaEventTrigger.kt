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
import io.github.m1n1m1.easymatic.domain.model.items.HaEvent
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

@Serializable
data class HaEventTriggerConfig(
    @Label("Home Assistant")
    @Picker(PickerKind.HA_HUB)
    val hub: String = "",
    @Label("Event type")
    val eventType: String = "",
    @Label("Data contains")
    val dataContains: String = "",
)

/**
 * Starts a macro when a named event fires on a Home Assistant event bus.
 *
 * The escape hatch beside `trigger.ha_state`, and it reaches things no state can: a
 * Zigbee remote's double-click (`zha_event`), an NFC tag presented to a Home Assistant
 * reader (`tag_scanned`), a doorbell press, and any event a user's own automation fires
 * at Easymatic on purpose. None of those is a *state* — they are things that happened,
 * with no resting value, which is the same exemption `trigger.sms` and `trigger.nfc`
 * take from the value-node rule. So there is no `value.ha_event` and never will be.
 *
 * **The event type is typed, not picked**, and this is the one Home Assistant field that
 * is. Every other identifier in this integration comes from the hub's snapshot, which is
 * complete for that server — but Home Assistant publishes **no way to list event
 * types**. The set is whatever integrations happen to fire, discoverable only by
 * watching the bus in the developer tools, and a chooser that could only offer types
 * already seen would hide the one the user is setting this up for. `zha_event` is also
 * legible in `@WifiNetwork`'s sense: read it back and a mistake is visible.
 *
 * **A blank event type is refused rather than meaning "everything".** The protocol
 * allows subscribing to the whole bus, and on a real install that is hundreds of frames
 * a minute delivered to one macro — which is not a filter anybody meant, and is a
 * battery cost nobody chose.
 */
class HaEventTrigger : Trigger<HaEventTriggerConfig, HaEvent> {

    override val definition = triggerNode<HaEventTriggerConfig, HaEvent>(
        typeId = "trigger.ha_event",
        displayName = "Home Assistant Event",
        description =
            "Starts when an event fires on the Home Assistant event bus — a Zigbee button, a tag, a custom event",
        category = NodeCategory.SMART_HOME_EVENTS,
        icon = NodeIcon.HOME,
        output = dataOut<HaEvent>("event", label = "Event"),
    )

    override fun activate(
        config: HaEventTriggerConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<HaEvent>> = flow {
        val reference = HomeAssistantRef.parse(config.hub)
        val eventType = config.eventType.trim()
        if (reference == null) {
            host.report(node, "No Home Assistant hub chosen, so this trigger cannot fire", LogLevel.WARN)
            return@flow
        }
        if (eventType.isBlank()) {
            host.report(node, "No event type given — name one, such as \"zha_event\"", LogLevel.WARN)
            return@flow
        }
        if (host.smartHomeHub(reference.hubId) == null) {
            host.report(node, "The Home Assistant hub this watches has been removed", LogLevel.WARN)
            return@flow
        }
        host.report(node, "Watching for \"$eventType\"")
        val handle = host.armHomeAssistantWatch(
            nodeId = node.id,
            spec = HaWatchSpec.EventWatch(reference.hubId, eventType),
            onReport = { message, level -> host.report(node, message, level) },
        )
        try {
            host.busEventsFor(node.id)
                .filter { it.source == TriggerSource.HOME_ASSISTANT && it.triggerNodeId == node.id }
                .filter { it.payload[HaPayload.EVENT_TYPE] == eventType }
                .map { it.toHaEvent() }
                .filter { matchesEvent(config, it) }
                .collect { emit(NodeOutput(it)) }
        } finally {
            handle.cancel()
        }
    }
}

/**
 * Whether this event is one the node was asked about.
 *
 * [HaEventTriggerConfig.dataContains] is a plain substring test over the event's JSON
 * rather than a path expression, deliberately: a path would need a syntax to learn and
 * would only cover the shapes somebody anticipated, where "does the payload mention
 * `double_press` anywhere?" is exactly the question people ask of an event whose shape
 * they have not read. Anything sharper is `transform.json_read` downstream, where the
 * whole payload is available and the comparison is visible on the canvas.
 */
internal fun matchesEvent(config: HaEventTriggerConfig, event: HaEvent): Boolean =
    config.dataContains.isBlank() || event.data.contains(config.dataContains.trim(), ignoreCase = true)

internal fun TriggerEvent.toHaEvent(): HaEvent = HaEvent(
    eventType = payload[HaPayload.EVENT_TYPE].orEmpty(),
    data = payload[HaPayload.EVENT_DATA].orEmpty(),
    origin = payload[HaPayload.ORIGIN].orEmpty(),
    firedAt = timestamp,
)
