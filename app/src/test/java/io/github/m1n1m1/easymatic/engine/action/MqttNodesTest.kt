package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.domain.model.HubRef
import io.github.m1n1m1.easymatic.domain.model.items.MqttMessage
import io.github.m1n1m1.easymatic.domain.model.schema.DateTime
import io.github.m1n1m1.easymatic.engine.DefaultExecutionContext
import io.github.m1n1m1.easymatic.engine.FakeMqtt
import io.github.m1n1m1.easymatic.engine.RecordingSystemServices
import io.github.m1n1m1.easymatic.engine.trigger.MqttTriggerConfig
import io.github.m1n1m1.easymatic.engine.trigger.matchesMessage
import io.github.m1n1m1.easymatic.engine.value.MqttTopicValue
import io.github.m1n1m1.easymatic.engine.value.MqttTopicValueConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three MQTT nodes.
 *
 * `HaNodesTest`'s shape and its emphasis: most of these are about what happens when
 * something is wrong, because that is the contract these nodes actually have — **report
 * it, put it on the data port, and pulse `out` anyway**. The recurring assertion is that
 * nothing reached the connection, which matters more here than for Home Assistant: a
 * broker's answer to an illegal publish is to drop the connection, taking every other
 * macro's triggers with it.
 */
class MqttNodesTest {

    private val mqtt = FakeMqtt()
    private val context = DefaultExecutionContext(
        systemServices = RecordingSystemServices(),
        mqtt = mqtt,
    )

    private val publish = MqttPublishAction()
    private val value = MqttTopicValue()

    private fun broker(id: String = "hub-1", name: String = "Loft broker") = HubRef.format(id, name)

    // ---- action.mqtt_publish ----

    @Test
    fun `a publish with no broker chosen never reaches a connection`() = runBlocking {
        val out = publish.execute(MqttPublishConfig(broker = "", topic = "a/b"), context)

        assertFalse(out.value.published)
        assertTrue(out.value.error.contains("No MQTT broker chosen"))
        assertTrue(mqtt.published.isEmpty())
    }

    /**
     * Names the string it read rather than only that it failed: a spec that arrived down a
     * wire can be anything at all, and seeing it in the error is the whole diagnosis.
     */
    @Test
    fun `a reference that is not a broker is reported with what it said`() = runBlocking {
        val out = publish.execute(MqttPublishConfig(broker = "Loft broker", topic = "a/b"), context)

        assertFalse(out.value.published)
        assertTrue(out.value.error.contains("Loft broker"))
        assertTrue(mqtt.published.isEmpty())
    }

    @Test
    fun `a publish carries the topic, payload, delivery level and retain flag through`() = runBlocking {
        val out = publish.execute(
            MqttPublishConfig(
                broker = broker(),
                topic = "  zigbee2mqtt/desk lamp/set  ",
                payload = """{"state":"ON"}""",
                quality = PublishQuality.EXACTLY_ONCE,
                retain = true,
            ),
            context,
        )

        assertTrue(out.value.published)
        val sent = mqtt.published.single()
        assertEquals("hub-1", sent.hubId)
        // Trimmed, because a topic pasted out of a dashboard arrives with whitespace and a
        // broker treats " a/b" and "a/b" as different topics.
        assertEquals("zigbee2mqtt/desk lamp/set", sent.topic)
        assertEquals("""{"state":"ON"}""", sent.payload)
        assertEquals(2, sent.qos)
        assertTrue(sent.retain)
    }

    /**
     * The default that matters: at-most-once is the tempting default for being cheapest,
     * and a message dropped under load looks exactly like a macro that did not run.
     */
    @Test
    fun `an unconfigured node publishes at least once`() = runBlocking {
        publish.execute(MqttPublishConfig(broker = broker(), topic = "a/b"), context)

        assertEquals(1, mqtt.published.single().qos)
        assertFalse(mqtt.published.single().retain)
    }

    @Test
    fun `a broker that refuses lands on the port rather than stopping the macro`() = runBlocking {
        mqtt.publishFailure = "The broker refused the username or password"

        val out = publish.execute(MqttPublishConfig(broker = broker(), topic = "a/b"), context)

        assertFalse(out.value.published)
        assertEquals("The broker refused the username or password", out.value.error)
        assertEquals("a/b", out.value.topic)
    }

    // ---- value.mqtt_topic ----

    @Test
    fun `the value reads the payload for its exact topic`() = runBlocking {
        mqtt.put("hub-1", "home/kitchen/temp", "21.4")

        val read = value.read(
            MqttTopicValueConfig(broker = broker(), topic = " home/kitchen/temp "),
            context,
        )

        assertEquals("21.4", read)
    }

    /**
     * Null rather than blank, so the consumer falls back to its own form value and a
     * comparison fails closed — the declared degradation for a value that cannot read.
     */
    @Test
    fun `the value answers null for a broker or topic it has nothing for`() = runBlocking {
        mqtt.put("hub-1", "home/kitchen/temp", "21.4")

        assertNull(value.read(MqttTopicValueConfig(broker = broker(), topic = "home/hall/temp"), context))
        assertNull(value.read(MqttTopicValueConfig(broker = broker("hub-2"), topic = "home/kitchen/temp"), context))
        assertNull(value.read(MqttTopicValueConfig(broker = "", topic = "home/kitchen/temp"), context))
        assertNull(value.read(MqttTopicValueConfig(broker = "not a reference", topic = "a"), context))
    }

    // ---- trigger.mqtt_message ----

    private fun message(payload: String = "", retained: Boolean = false) = MqttMessage(
        topic = "zigbee2mqtt/button/action",
        payload = payload,
        retained = retained,
        receivedAt = DateTime(0),
    )

    /**
     * The default that decides whether this node looks broken. A broker hands over every
     * retained message the instant a subscription is accepted, so with this on the macro
     * fires at every arm — at boot, after a re-enable, after every edit — carrying a value
     * that may be months old.
     */
    @Test
    fun `a retained message is ignored unless the node asked for it`() {
        val ignoring = MqttTriggerConfig()
        val including = MqttTriggerConfig(includeRetained = true)

        assertFalse(matchesMessage(ignoring, message(retained = true)))
        assertTrue(matchesMessage(ignoring, message(retained = false)))
        assertTrue(matchesMessage(including, message(retained = true)))
    }

    @Test
    fun `the payload filter is a substring test and is case insensitive`() {
        val config = MqttTriggerConfig(payloadContains = " double ".trim())

        assertTrue(matchesMessage(config, message(payload = """{"action":"double_press"}""")))
        assertTrue(matchesMessage(config, message(payload = "DOUBLE")))
        assertFalse(matchesMessage(config, message(payload = """{"action":"single"}""")))
    }

    @Test
    fun `a blank payload filter matches everything that arrived`() {
        assertTrue(matchesMessage(MqttTriggerConfig(), message(payload = "anything")))
        assertTrue(matchesMessage(MqttTriggerConfig(), message(payload = "")))
    }
}
