package io.github.m1n1m1.easymatic.data.mqtt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a topic filter means.
 *
 * The routing table matches with these rather than the broker doing it, because one
 * connection is shared by every armed node — so a filter read wrongly here is a trigger
 * that never fires or one that fires on everything, and neither says anything about why.
 */
class MqttTopicsTest {

    @Test
    fun `an exact filter matches only itself`() {
        assertTrue(MqttTopics.matches("home/kitchen/temp", "home/kitchen/temp"))
        assertFalse(MqttTopics.matches("home/kitchen/temp", "home/kitchen/temp/raw"))
        assertFalse(MqttTopics.matches("home/kitchen/temp", "home/kitchen"))
        assertFalse(MqttTopics.matches("home/kitchen/temp", "home/hall/temp"))
    }

    @Test
    fun `a plus matches exactly one level`() {
        assertTrue(MqttTopics.matches("home/+/temp", "home/kitchen/temp"))
        assertTrue(MqttTopics.matches("home/+/temp", "home/hall/temp"))
        assertFalse(MqttTopics.matches("home/+/temp", "home/kitchen/inner/temp"))
        // One level, and one is not none.
        assertFalse(MqttTopics.matches("home/+/temp", "home/temp"))
    }

    @Test
    fun `a plus matches an empty level, which is a real topic`() {
        assertTrue(MqttTopics.matches("home/+/temp", "home//temp"))
    }

    /**
     * The rule people are surprised by, and the reason it is pinned: `sport/#` matching
     * `sport` itself is in the specification, so a macro watching `zigbee2mqtt/#` hears
     * the bridge's own `zigbee2mqtt` topic as well.
     */
    @Test
    fun `a hash matches the rest of the tree including nothing at all`() {
        assertTrue(MqttTopics.matches("home/#", "home/kitchen/temp"))
        assertTrue(MqttTopics.matches("home/#", "home/kitchen"))
        assertTrue(MqttTopics.matches("home/#", "home"))
        assertFalse(MqttTopics.matches("home/#", "house/kitchen"))
    }

    @Test
    fun `a bare hash matches everything that is not the broker's own`() {
        assertTrue(MqttTopics.matches("#", "a"))
        assertTrue(MqttTopics.matches("#", "a/b/c"))
    }

    /**
     * Without the `$` carve-out, a macro watching `#` to see what a broker publishes is
     * woken several times a second by the broker's own statistics — which reads as the
     * trigger being broken rather than as a rule of the protocol.
     */
    @Test
    fun `a leading wildcard does not reach the broker's own topics`() {
        assertFalse(MqttTopics.matches("#", "\$SYS/broker/uptime"))
        assertFalse(MqttTopics.matches("+/broker/uptime", "\$SYS/broker/uptime"))
        // Named explicitly, they are reachable — which is how a macro can watch one.
        assertTrue(MqttTopics.matches("\$SYS/#", "\$SYS/broker/uptime"))
    }

    @Test
    fun `a topic with wildcards in it cannot be published to`() {
        assertTrue(MqttTopics.isPublishable("zigbee2mqtt/desk lamp/set"))
        assertFalse(MqttTopics.isPublishable("home/+/set"))
        assertFalse(MqttTopics.isPublishable("home/#"))
        assertFalse(MqttTopics.isPublishable(""))
    }

    @Test
    fun `a space is legal, because real devices publish under names containing one`() {
        assertTrue(MqttTopics.isPublishable("zigbee2mqtt/Living room lamp/set"))
        assertTrue(MqttTopics.isSubscribable("zigbee2mqtt/Living room lamp/+"))
        assertTrue(MqttTopics.matches("zigbee2mqtt/+/state", "zigbee2mqtt/Living room lamp/state"))
    }

    @Test
    fun `a wildcard has to occupy a whole level`() {
        assertTrue(MqttTopics.isSubscribable("home/+/state"))
        assertTrue(MqttTopics.isSubscribable("home/#"))
        assertTrue(MqttTopics.isSubscribable("#"))
        assertFalse(MqttTopics.isSubscribable("home/te+t"))
        assertFalse(MqttTopics.isSubscribable("home/te#"))
        // `#` must be last, so a filter cannot carry on past it.
        assertFalse(MqttTopics.isSubscribable("home/#/state"))
        assertFalse(MqttTopics.isSubscribable(""))
    }
}
