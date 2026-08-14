package com.example.ottomatic.engine.value

import com.example.ottomatic.domain.model.HubRef
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Picker
import com.example.ottomatic.domain.model.config.PickerKind
import com.example.ottomatic.domain.model.config.Suggested
import com.example.ottomatic.domain.model.config.SuggestionSource
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.ValueNode
import com.example.ottomatic.engine.valueNode
import kotlinx.serialization.Serializable

@Serializable
data class MqttTopicValueConfig(
    @Label("Broker")
    @Picker(PickerKind.MQTT_BROKER)
    val broker: String = "",
    @Label("Topic")
    @Suggested(SuggestionSource.MQTT_TOPIC, scopedBy = ["broker"])
    val topic: String = "",
)

/**
 * The last message published to an MQTT topic.
 *
 * The second value node that reads over a network connection, after `value.ha_state`, and
 * it is legal on the pull side for that node's reason: **the bar was never "must not
 * concern the network"** but "must be cheap and must not fail", and a push channel clears
 * it where a request-response API cannot. A connection is held open for as long as the
 * engine runs, and every message it delivers lands in a `ConcurrentHashMap` — so the read
 * is a lookup.
 *
 * MQTT makes the argument cleaner than Home Assistant did, because the protocol has **no
 * read operation at all**. There is nothing this could have been a cached version of: a
 * subscriber is *told* values and remembers them, so a cache is the only thing that exists
 * to read. That also settles what "cheap" means here — there is no slower alternative to
 * fall back on.
 *
 * **The first read of a topic is the one that is not free**, and it is worth knowing
 * about. Nothing is subscribed speculatively, so a topic nothing has asked for yet is
 * subscribed on the first read and the read waits briefly for the broker's **retained**
 * message. State topics are retained by convention and by every major publisher —
 * Zigbee2MQTT, Tasmota, ESPHome — so in practice the first read answers and every read
 * after it is a map lookup. A topic with no retained message answers null until something
 * publishes to it, which is not a degradation but the truth: until then, nothing anywhere
 * knows that value.
 *
 * **An exact topic and never a filter.** `home/+/temperature` names many topics and so has
 * many last messages, and silently picking one of them would be right until a second
 * sensor was added. Watching a filter is `trigger.mqtt_message`, where every match is
 * delivered rather than one being chosen.
 *
 * **Text out, not a struct**, on `value.ha_state`'s reasoning: one DATA output is the value
 * contract, the question is "is the boiler on?" or "what is the temperature?", and a struct
 * would put an `action.break` in front of every comparison. A JSON payload reaches
 * `transform.json_read` as text, and a number reaches a numeric port through the
 * `transform.convert` autocast drops into the wire.
 *
 * **Not offered as a `val:` comparison source**, joining `value.variable` and
 * `value.ha_state` for the identical reason: a `val:` read is performed with no config, and
 * this node's whole answer is a matter of which broker and topic were chosen. Offering it
 * would offer a comparison that silently never matched. Comparing a topic means wiring this
 * node into the `source` port, which is one drag.
 */
class MqttTopicValue : ValueNode<MqttTopicValueConfig, String> {

    override val definition = valueNode<MqttTopicValueConfig, String>(
        typeId = "value.mqtt_topic",
        displayName = "MQTT Topic",
        description = "The last message published to a topic on an MQTT broker",
        category = NodeCategory.VALUE_SMART_HOME,
        icon = NodeIcon.HOME,
        output = dataOut("payload", label = "Message"),
    )

    override suspend fun read(config: MqttTopicValueConfig, context: ExecutionContext): String? {
        val broker = HubRef.parse(config.broker) ?: return null
        return context.mqtt.lastMessage(broker.hubId, config.topic.trim())?.payload
    }
}
