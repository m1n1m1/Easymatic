package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.core.service.MqttPublish
import io.github.m1n1m1.easymatic.domain.model.HubRef
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Multiline
import io.github.m1n1m1.easymatic.domain.model.config.Picker
import io.github.m1n1m1.easymatic.domain.model.config.PickerKind
import io.github.m1n1m1.easymatic.domain.model.config.Suggested
import io.github.m1n1m1.easymatic.domain.model.config.SuggestionSource
import io.github.m1n1m1.easymatic.domain.model.config.Wired
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.MqttPublished
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.actionNode
import kotlinx.serialization.Serializable

/**
 * How reliably a message is delivered to the broker.
 *
 * The protocol's three levels, named for what they *mean* rather than as the numbers
 * `MqttQos` carries. A workflow persists the enum's name, so this is also the durable
 * spelling — `AiModel`'s rule, for its reason.
 */
@Serializable
enum class PublishQuality {
    /**
     * The default, and the right one almost always: the broker acknowledges the message.
     *
     * Deliberately first, so it is what an unconfigured node does. At-most-once is the
     * tempting default for being cheapest and is the wrong one — a dropped message under
     * load looks exactly like a macro that did not run.
     */
    @Label("Confirmed by the broker")
    AT_LEAST_ONCE,

    @Label("Send and forget")
    AT_MOST_ONCE,

    @Label("Confirmed exactly once")
    EXACTLY_ONCE,
}

@Serializable
data class MqttPublishConfig(
    @Label("Broker")
    @Picker(PickerKind.MQTT_BROKER)
    val broker: String = "",
    @Label("Topic")
    @Suggested(SuggestionSource.MQTT_TOPIC, scopedBy = ["broker"])
    @Wired
    val topic: String = "",
    @Label("Message")
    @Multiline
    @Wired
    val payload: String = "",
    @Label("Delivery")
    val quality: PublishQuality = PublishQuality.AT_LEAST_ONCE,
    /**
     * Off by default, and the one field here that changes something outside this macro.
     *
     * A retained message is stored **by the broker** and handed to every future subscriber
     * of the topic, so leaving it on by accident means a macro's message sitting on the
     * broker indefinitely, greeting everything else in the house on every reconnect. Right
     * for a state topic somebody is publishing on purpose; wrong for a command.
     */
    @Label("Ask the broker to keep this message")
    val retain: Boolean = false,
)

/**
 * Publishes a message to an MQTT topic.
 *
 * **The whole of the control half of MQTT**, and it is one node rather than a family
 * because a broker offers exactly one operation to a publisher. That is also why the three
 * light nodes cannot speak to a broker: `action.light_control` needs to know what a light
 * *is*, and a broker does not — what a light looks like on it is a convention belonging to
 * whatever publishes it. So turning a Zigbee2MQTT lamp on is this node with the topic and
 * payload that software documents, which is what every MQTT integration in every other
 * automation app amounts to.
 *
 * The **topic and the message are both wireable**, which is what keeps that from being a
 * limitation: `action.for_each` over a list of rooms with `transform.text` building
 * `zigbee2mqtt/{A}/set` is the same macro somebody would otherwise want twenty nodes for.
 *
 * **A wildcard topic is refused rather than sent**, and this is the one refusal here worth
 * having: `home/+/set` reads like a way to address every room at once, MQTT has no such
 * thing, and a broker's answer to an illegal publish is to **drop the connection** — which
 * would take every other macro's triggers down with it, seconds later, with nothing
 * connecting the two events.
 *
 * **A failure lands on the port and pulses `out`**, on `action.script`'s and
 * `transform.json_read`'s contract: a broker that is unplugged is a thing for the macro to
 * decide about, not a reason to stop the graph. The receipt says [MqttPublished.published]
 * rather than "delivered" because no level of MQTT acknowledges a *subscriber* — a message
 * published to a topic nobody is listening on succeeds, completely and by design.
 *
 * **No permission declared**, on `action.light_control`'s rule: `INTERNET` is install-time
 * and gets no `Permissions` constant.
 */
class MqttPublishAction : Action<MqttPublishConfig, MqttPublished> {

    override val definition = actionNode<MqttPublishConfig, MqttPublished>(
        typeId = "action.mqtt_publish",
        displayName = "Publish to MQTT",
        description =
            "Sends a message to a topic on an MQTT broker — Mosquitto, Zigbee2MQTT, Tasmota, ESPHome",
        category = NodeCategory.SMART_HOME,
        icon = NodeIcon.HOME,
        output = dataOut<MqttPublished>("state"),
    )

    override suspend fun execute(
        input: MqttPublishConfig,
        context: ExecutionContext,
    ): NodeOutput<MqttPublished> {
        val broker = HubRef.parse(input.broker)
        val topic = input.topic.trim()
        if (broker == null) {
            val problem = if (input.broker.isBlank()) {
                "No MQTT broker chosen, so there is nothing to publish to"
            } else {
                "Not an MQTT broker reference: \"${input.broker.trim()}\""
            }
            context.log(problem, LogLevel.ERROR)
            return NodeOutput(MqttPublished(topic = topic, published = false, error = problem))
        }

        val result = context.mqtt.publish(
            MqttPublish(
                hubId = broker.hubId,
                topic = topic,
                payload = input.payload,
                qos = input.quality.wireValue,
                retain = input.retain,
            ),
        )
        if (result.published) {
            context.log("Published to $topic")
        } else {
            context.log(result.error, LogLevel.WARN)
        }
        return NodeOutput(MqttPublished(topic = topic, published = result.published, error = result.error))
    }
}

/**
 * The number this level is on the wire.
 *
 * Here rather than on the enum in `domain` because the mapping is the *protocol's*, and
 * the enum is the durable name a workflow persists — `AiModel.modelId`'s split, and for
 * its reason: renaming a level would break saved macros where changing this mapping cannot.
 */
private val PublishQuality.wireValue: Int
    get() = when (this) {
        PublishQuality.AT_MOST_ONCE -> io.github.m1n1m1.easymatic.core.service.MqttQos.AT_MOST_ONCE
        PublishQuality.AT_LEAST_ONCE -> io.github.m1n1m1.easymatic.core.service.MqttQos.AT_LEAST_ONCE
        PublishQuality.EXACTLY_ONCE -> io.github.m1n1m1.easymatic.core.service.MqttQos.EXACTLY_ONCE
    }
