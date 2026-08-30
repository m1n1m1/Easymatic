package io.github.m1n1m1.easymatic.engine.trigger

/**
 * What `TriggerHost.armMqttWatch` needs in order to route messages to a node.
 *
 * A data class rather than a sealed interface, unlike [HaWatchSpec] and [MailWatchSpec],
 * because MQTT has exactly one thing to watch: a topic filter. There is no second shape
 * to tell apart, and a sealed hierarchy with one member would only invite a second that
 * the protocol does not have.
 *
 * The filters a user typed are deliberately **not** here, on [HaWatchSpec]'s reasoning:
 * "the payload contains" and "ignore retained messages" are predicates the trigger applies
 * per message, so editing one takes effect with no re-arm signal to invent. What is here
 * is what decides the *subscription* and the *routing table* — which broker, and which
 * filter — because neither can be decided per message without delivering every message to
 * every node first.
 */
data class MqttWatchSpec(
    val hubId: String,
    /** An MQTT topic filter, wildcards and all: `zigbee2mqtt/+/action`, `home/#`. */
    val topicFilter: String,
)

/**
 * The payload keys an MQTT message carries.
 *
 * Declared once and read from both ends — the `data/` side that fills them and the trigger
 * that reads them — on [HaPayload]'s and [MailPayload]'s shape and for their reason: a key
 * spelled two ways compiles perfectly and delivers nothing.
 *
 * [PAYLOAD] is **text and never parsed**, which is forced by `TriggerEvent.payload` being
 * `Map<String, String>` and is also the right answer: what a payload means belongs to
 * whatever published it, and `transform.json_read` walks a JSON one with `battery` or
 * `action`. That is `action.http`'s road and the rule `ApiInputs` already states.
 */
object MqttPayload {
    const val HUB_ID = "hubId"
    const val TOPIC = "topic"
    const val PAYLOAD = "payload"
    const val RETAINED = "retained"
    const val QOS = "qos"
    const val RECEIVED_AT = "receivedAt"
}
