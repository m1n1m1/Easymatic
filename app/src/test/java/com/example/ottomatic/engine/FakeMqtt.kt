package com.example.ottomatic.engine

import com.example.ottomatic.core.service.Mqtt
import com.example.ottomatic.core.service.MqttPublish
import com.example.ottomatic.core.service.MqttPublishResult
import com.example.ottomatic.core.service.MqttReading

/**
 * An MQTT broker that records what was published and answers from a map.
 *
 * [FakeHomeAssistant]'s shape, and it records for the same reason: half of what the MQTT
 * nodes promise is about what **does not** reach the connection — a wildcard topic, a
 * broker that was never chosen, a reference that parses as nothing. A fake that only
 * returned answers could not tell a refusal from a broker that happened to be down.
 */
class FakeMqtt(
    var publishFailure: String? = null,
    var connected: Boolean = true,
) : Mqtt {

    /** What the cache holds, keyed `hubId` to `topic` to reading. */
    val messages = mutableMapOf<String, MutableMap<String, MqttReading>>()

    val published = mutableListOf<MqttPublish>()

    /** Puts one message in the cache. */
    fun put(hubId: String, topic: String, payload: String, retained: Boolean = false) {
        messages.getOrPut(hubId) { mutableMapOf() }[topic] =
            MqttReading(topic = topic, payload = payload, retained = retained)
    }

    override suspend fun publish(request: MqttPublish): MqttPublishResult {
        published += request
        return publishFailure
            ?.let { MqttPublishResult(published = false, error = it) }
            ?: MqttPublishResult(published = true)
    }

    override suspend fun lastMessage(hubId: String, topic: String): MqttReading? =
        messages[hubId]?.get(topic)

    override fun isConnected(hubId: String): Boolean = connected
}
