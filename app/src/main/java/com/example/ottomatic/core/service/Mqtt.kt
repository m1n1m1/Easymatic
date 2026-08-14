package com.example.ottomatic.core.service

/**
 * An MQTT broker, as the engine sees it.
 *
 * **Ottomatic is a client and never a broker.** Nothing here listens for connections;
 * every member is something this phone says to, or hears from, a broker somebody else is
 * running — the Mosquitto in a cupboard, the one inside a Home Assistant install, the one
 * Zigbee2MQTT already publishes to. That is worth stating on the facade because "MQTT
 * support" is ambiguous everywhere else it is written, and the ambiguity would be
 * expensive: a broker is a service with a port to open, a retained-message store to keep
 * and an authentication model to get wrong, and none of that is in this app.
 *
 * A facade of its own beside [SmartHome] rather than more members on it, and the line is
 * the one [HomeAssistant] already drew: [SmartHome] is the **vendor-neutral view of
 * lights**, and a topic is not a light. What a light looks like on a broker is a
 * convention belonging to whatever publishes it — Zigbee2MQTT's `exposes`, Home
 * Assistant's discovery topics, Tasmota's — and the broker itself knows none of them. So
 * there is no `SmartHomeVendor` for MQTT and the three light nodes are not offered a
 * broker; a light is turned on here by publishing to the topic its own software
 * documents, which is what every MQTT integration in every other automation app does.
 *
 * **Nothing here throws.** Every member answers a result carrying an error string, or
 * null, on [SmartHome]'s and [Mail]'s rule: a downstream `action.if` sees one shape
 * whether the message went, the broker was unplugged or the password was refused.
 *
 * **The second facade both sides of the graph may touch**, after [HomeAssistant], and for
 * exactly its reason — which is the reason worth carrying forward, because it is about
 * the *transport* rather than the subject:
 *
 * - [lastMessage] is a **map lookup**, served from a cache the connection keeps warm for
 *   as long as the engine process runs. Cheap, repeatable and incapable of failing, which
 *   is the pull side's actual contract, so `value.mqtt_topic` is legal where
 *   `value.light_state` is not.
 * - [publish] is a **side effect over the network**, and is an action's alone.
 *
 * MQTT makes that split unusually clean: the protocol has no "read this topic" operation
 * at all. A subscriber is *told* values and remembers them, so the cache is not an
 * optimisation of a request that could have been made — it is the only thing there is.
 */
interface Mqtt {

    /**
     * Publishes [request], or reports why it did not.
     *
     * The action side, and the only member with an effect.
     */
    suspend fun publish(request: MqttPublish): MqttPublishResult

    /**
     * The last message seen on an exact [topic], or null when there is none.
     *
     * Null covers cases a caller cannot act on differently: no such hub, a broker whose
     * password cannot be read, a connection that is down, and a topic nothing has
     * published since the app started. The consumer falls back to its own form value and
     * a comparison fails closed, which is the declared degradation for a value that
     * cannot read.
     *
     * **An exact topic and never a filter.** `home/+/temperature` matches many topics and
     * so has many last messages, and a value node that silently picked one of them would
     * be right until a second sensor was added. Filters belong to `trigger.mqtt_message`,
     * where every match is delivered rather than one being chosen.
     *
     * **Suspending, but not a round trip.** It waits only while a connection that has
     * just opened finishes replaying the broker's retained messages, and that wait is
     * bounded and happens once per connection rather than once per read.
     */
    suspend fun lastMessage(hubId: String, topic: String): MqttReading?

    /** Whether [hubId]'s connection is live, for the hub detail screen. */
    fun isConnected(hubId: String): Boolean
}

/**
 * One message as the cache holds it.
 *
 * [payload] is the bytes as text, and is deliberately **not** parsed here. What a payload
 * means is entirely the publisher's business — `ON`, `21.4`, a JSON object, a protobuf —
 * the graph is strictly typed, and `transform.convert` and `transform.json_read` are the
 * visible places a reading belongs. `value.mqtt_topic` answers text and autocast drops a
 * conversion node in when a numeric port needs one, which is `value.ha_state`'s road.
 *
 * A payload that is not valid text arrives as the replacement character rather than as an
 * error: a macro watching a topic that turns out to carry binary should see something odd
 * on the canvas, not a trigger that stops firing.
 */
data class MqttReading(
    val topic: String,
    val payload: String,
    /**
     * Whether the broker had this stored rather than it arriving live.
     *
     * Carried because it is the difference between "the boiler is on" and "the boiler was
     * on when it last said anything, which may have been in March".
     */
    val retained: Boolean = false,
    val qos: Int = 0,
    val receivedAtEpochMs: Long = 0,
)

/**
 * One publish.
 *
 * [retain] is the field worth understanding before using: it asks the **broker** to keep
 * this message and hand it to every future subscriber of the topic. That is how a state
 * topic stays meaningful across a restart, and it is also how a macro can leave a message
 * sitting on a broker indefinitely for everything else in the house to receive. It is off
 * by default for that reason.
 */
data class MqttPublish(
    val hubId: String,
    val topic: String,
    val payload: String,
    val qos: Int = MqttQos.AT_LEAST_ONCE,
    val retain: Boolean = false,
)

/**
 * [published] rather than "delivered", and the distinction is the protocol's rather than
 * this app's caution.
 *
 * At QoS 0 it means the bytes were handed to the socket. At QoS 1 and 2 it means the
 * broker acknowledged them. **No QoS says anything about a subscriber**, because MQTT has
 * no such acknowledgement — a message published to a topic nobody is listening to
 * succeeds, completely and by design. Claiming otherwise would be the one thing this
 * integration must not do, which is `ServiceCallResult`'s sentence in a second setting.
 */
data class MqttPublishResult(
    val published: Boolean,
    val error: String = "",
)

/**
 * The three delivery guarantees, as the numbers the wire carries.
 *
 * Plain constants rather than an enum because this is the protocol's own numbering and
 * every library, broker and documentation page speaks it — a node's config enum maps onto
 * these, and everything below that point passes the number through.
 */
object MqttQos {
    /** Fire and forget: the broker may never receive it, and nothing will say so. */
    const val AT_MOST_ONCE = 0

    /** Acknowledged, and may arrive more than once. The default, and what most brokers are tuned for. */
    const val AT_LEAST_ONCE = 1

    /** Acknowledged exactly once, over a four-packet handshake. */
    const val EXACTLY_ONCE = 2
}

/**
 * No broker available: every call fails closed, naming the one thing the user can do
 * about it.
 *
 * The engine-only default, so a test that builds an
 * [com.example.ottomatic.engine.ExecutionContext] without a broker sees exactly what a
 * phone with an empty hub library reports — which the nodes already have to handle.
 */
object NoMqtt : Mqtt {

    override suspend fun publish(request: MqttPublish) = MqttPublishResult(published = false, error = UNAVAILABLE)

    override suspend fun lastMessage(hubId: String, topic: String): MqttReading? = null

    override fun isConnected(hubId: String): Boolean = false

    private const val UNAVAILABLE = "No MQTT broker is set up on this phone"
}
