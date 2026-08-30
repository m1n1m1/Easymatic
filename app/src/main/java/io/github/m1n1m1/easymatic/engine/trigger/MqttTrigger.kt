package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.core.trigger.TriggerEvent
import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.domain.model.HubRef
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Picker
import io.github.m1n1m1.easymatic.domain.model.config.PickerKind
import io.github.m1n1m1.easymatic.domain.model.config.Suggested
import io.github.m1n1m1.easymatic.domain.model.config.SuggestionSource
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.MqttMessage
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

@Serializable
data class MqttTriggerConfig(
    @Label("Broker")
    @Picker(PickerKind.MQTT_BROKER)
    val broker: String = "",
    @Label("Topic")
    @Suggested(SuggestionSource.MQTT_TOPIC, scopedBy = ["broker"])
    val topicFilter: String = "",
    @Label("Message contains")
    val payloadContains: String = "",
    /**
     * Off by default, and the default that matters most on this node.
     *
     * A broker hands over every **retained** message on the topics a subscriber asks for,
     * the instant it subscribes. So a macro armed with this on fires immediately on every
     * arm — at boot, after a re-enable, after every edit — carrying a value that may be
     * months old. That reads as the trigger firing at random, and it is the single
     * likeliest way for this node to look broken.
     *
     * It is offered rather than simply suppressed because there is one real use: reacting
     * to what a topic *already says* at start-up, which is how "if the door was left open
     * overnight, tell me at boot" is written.
     */
    @Label("Also fire on messages the broker had stored")
    val includeRetained: Boolean = false,
)

/**
 * Starts a macro when a message arrives on an MQTT topic.
 *
 * **The whole of the event half of MQTT**, and the reason a broker is worth connecting to
 * at all: a Zigbee button press, a door sensor, a power meter, an ESPHome device's status
 * and anything any other automation system in the house publishes are all one topic away,
 * with no vendor code anywhere and no integration to maintain. A broker that has never
 * heard of Easymatic works on the day it is switched on.
 *
 * **The filter is typed and its dropdown is a suggestion**, which is `@WifiNetwork`'s shape
 * for a sharper version of its argument. A broker publishes **no directory of its topics**
 * — the only way to learn one is to be subscribed when something publishes to it — so a
 * chooser could only ever offer what happened to be spoken during a Refresh, and the
 * commonest case is setting a macro up for a device that is currently unplugged. On top of
 * that, the useful answers here are *filters* rather than topics: `zigbee2mqtt/+/action`
 * is not something any list could contain, because it is not a topic at all.
 *
 * **The routing is done before the event is emitted.** `MqttConnections` holds the
 * registration table and addresses each message to one node id, so this trigger does not
 * see — and is not woken by — anything it did not ask for. That is
 * `TriggerHost.sensorSamples`' rule, and it matters more here than for most: one shared
 * connection carries every macro's subscriptions, and a busy broker publishes constantly.
 *
 * **No `value.mqtt_message`.** The rule pairing a trigger with a value is served instead by
 * `value.mqtt_topic`, which is the same subject read the other way round — and it is
 * deliberately narrower: a value reads one exact topic where this watches a filter, because
 * a filter matching four topics has four last messages and no way to choose.
 *
 * **No permission declared**, on `action.light_control`'s rule: `INTERNET` is install-time
 * and gets no `Permissions` constant. What actually stops this node — no broker, a refused
 * password, an address that has moved — is reported into the macro's own console, where the
 * person who placed the node will see it.
 */
class MqttTrigger : Trigger<MqttTriggerConfig, MqttMessage> {

    override val definition = triggerNode<MqttTriggerConfig, MqttMessage>(
        typeId = "trigger.mqtt_message",
        displayName = "MQTT Message",
        description =
            "Starts when a message arrives on an MQTT topic — a Zigbee button, a sensor, any device that publishes",
        category = NodeCategory.SMART_HOME_EVENTS,
        icon = NodeIcon.HOME,
        output = dataOut<MqttMessage>("message", label = "Message"),
    )

    override fun activate(
        config: MqttTriggerConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<MqttMessage>> = flow {
        val broker = HubRef.parse(config.broker)
        val filter = config.topicFilter.trim()
        if (broker == null) {
            host.report(node, "No MQTT broker chosen, so this trigger cannot fire", LogLevel.WARN)
            return@flow
        }
        // A blank filter is refused rather than meaning "everything", on `trigger.ha_event`'s
        // reasoning: "#" is a legal answer somebody can type on purpose, and an empty box is
        // not somebody asking for every message in the house.
        if (filter.isBlank()) {
            host.report(node, "No topic given — name one, such as \"zigbee2mqtt/+/action\"", LogLevel.WARN)
            return@flow
        }
        if (host.smartHomeHub(broker.hubId) == null) {
            host.report(node, "The MQTT broker this watches has been removed", LogLevel.WARN)
            return@flow
        }
        host.report(node, "Watching \"$filter\"")
        val handle = host.armMqttWatch(
            nodeId = node.id,
            spec = MqttWatchSpec(broker.hubId, filter),
            onReport = { message, level -> host.report(node, message, level) },
        )
        try {
            host.busEventsFor(node.id)
                .filter { it.source == TriggerSource.MQTT && it.triggerNodeId == node.id }
                .map { it.toMqttMessage() }
                .filter { matchesMessage(config, it) }
                .collect { emit(NodeOutput(it)) }
        } finally {
            handle.cancel()
        }
    }
}

/**
 * Whether this message is one the node was asked about.
 *
 * The topic is **not** re-checked here, unlike `trigger.ha_event`'s type: the routing table
 * matched the filter before emitting, so re-testing it would only be a second chance to
 * disagree with the thing that already decided.
 *
 * [MqttTriggerConfig.payloadContains] is a plain substring test rather than a path
 * expression, on `HaEventTriggerConfig.dataContains`' reasoning: a path needs a syntax to
 * learn and only covers shapes somebody anticipated, where "does it mention `double`
 * anywhere?" is exactly the question people ask of a payload whose shape they have not
 * read. Anything sharper is `transform.json_read` downstream, where the whole payload is
 * available and the comparison is visible on the canvas.
 */
internal fun matchesMessage(config: MqttTriggerConfig, message: MqttMessage): Boolean {
    if (message.retained && !config.includeRetained) return false
    val needle = config.payloadContains.trim()
    return needle.isBlank() || message.payload.contains(needle, ignoreCase = true)
}

internal fun TriggerEvent.toMqttMessage(): MqttMessage = MqttMessage(
    topic = payload[MqttPayload.TOPIC].orEmpty(),
    payload = payload[MqttPayload.PAYLOAD].orEmpty(),
    retained = payload[MqttPayload.RETAINED].toBoolean(),
    qos = payload[MqttPayload.QOS]?.toIntOrNull() ?: 0,
    receivedAt = timestamp,
)
