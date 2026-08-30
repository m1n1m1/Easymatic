package io.github.m1n1m1.easymatic.data.mqtt

import io.github.m1n1m1.easymatic.core.service.Mqtt
import io.github.m1n1m1.easymatic.core.service.MqttPublish
import io.github.m1n1m1.easymatic.core.service.MqttPublishResult
import io.github.m1n1m1.easymatic.core.service.MqttReading
import io.github.m1n1m1.easymatic.data.SmartHomeHubRepository
import io.github.m1n1m1.easymatic.domain.model.SmartHomeKind

/**
 * [Mqtt] over the one shared connection per broker.
 *
 * The thin half of this integration, and deliberately so: everything about holding a
 * connection open, replaying subscriptions and keeping the cache warm is
 * [MqttConnections]', and what is left here is the guard clauses — which are the part
 * worth reading.
 *
 * **Both halves go over the same connection**, which is where this parts company with
 * `AndroidHomeAssistant`. There, a service call goes over REST while the cache is fed by a
 * websocket, because an action has to work in the seconds after a reconnect and a path
 * exercised only when something is broken is a path that is broken. MQTT offers no second
 * road: publishing *is* the protocol, so the connection is opened on demand if it is not
 * already up, which is the same guarantee reached differently.
 *
 * **Nothing here throws**, which is the facade's promise.
 */
internal class AndroidMqtt(
    private val hubs: SmartHomeHubRepository,
    private val connections: MqttConnections,
) : Mqtt {

    @Suppress("ReturnCount") // Each guard names a different thing the user has to fix.
    override suspend fun publish(request: MqttPublish): MqttPublishResult {
        if (request.hubId.isBlank()) return failed("No MQTT broker chosen on this node")
        val hub = hubs.get(request.hubId) ?: return failed(DELETED_HUB)
        if (hub.kind != SmartHomeKind.MQTT) return failed("\"${hub.name}\" is not an MQTT broker")
        if (!hub.isComplete) return failed(unfinished(hub.name))
        val topic = request.topic.trim()
        // Refused here rather than by the broker, which answers an illegal publish by
        // dropping the connection — taking every other macro's triggers down with it.
        if (!MqttTopics.isPublishable(topic)) return failed(badTopic(request.topic))

        val error = connections.publish(
            hubId = request.hubId,
            topic = topic,
            payload = request.payload,
            qos = request.qos,
            retain = request.retain,
        )
        return when (error) {
            null -> MqttPublishResult(published = true)
            else -> failed(error)
        }
    }

    override suspend fun lastMessage(hubId: String, topic: String): MqttReading? {
        if (hubId.isBlank() || topic.isBlank()) return null
        return connections.lastMessage(hubId, topic.trim())
    }

    override fun isConnected(hubId: String): Boolean = connections.isConnected(hubId)

    private fun failed(error: String) = MqttPublishResult(published = false, error = error)

    /**
     * The likeliest mistake in this field, worded so the fix is obvious.
     *
     * `home/+/set` reads like a way to address every room at once and is not a thing MQTT
     * can do — a wildcard is a *subscription* concept — so the sentence names the
     * character rather than saying the topic is invalid.
     */
    private fun badTopic(topic: String): String = when {
        topic.isBlank() -> "No topic given — name one, such as \"zigbee2mqtt/desk lamp/set\""
        else -> "\"${topic.trim()}\" cannot be published to: a topic may not contain + or #, " +
            "which only mean something when subscribing"
    }

    private fun unfinished(name: String): String =
        "\"$name\" has no usable address — open Smart home and check the broker's address"

    private companion object {
        const val DELETED_HUB = "This node points at an MQTT broker that no longer exists"
    }
}
