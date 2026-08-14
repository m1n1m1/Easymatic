package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.HomeAssistantRef
import com.example.ottomatic.domain.model.items.HaStateChange
import com.example.ottomatic.domain.model.schema.DateTime
import com.example.ottomatic.engine.DefaultExecutionContext
import com.example.ottomatic.engine.FakeHomeAssistant
import com.example.ottomatic.engine.RecordingSystemServices
import com.example.ottomatic.engine.trigger.HaEventTriggerConfig
import com.example.ottomatic.engine.trigger.HaStateTriggerConfig
import com.example.ottomatic.engine.trigger.matches
import com.example.ottomatic.engine.trigger.matchesEvent
import com.example.ottomatic.engine.value.HaStateValue
import com.example.ottomatic.engine.value.HaStateValueConfig
import com.example.ottomatic.domain.model.items.HaEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four Home Assistant nodes.
 *
 * `LightActionsTest`'s shape and its emphasis: most of these are about what happens when
 * something is wrong, because that is the contract these nodes actually have — **report
 * it, put it on the data port, and pulse `out` anyway**. The other recurring assertion is
 * that nothing reached the facade: a malformed reference must be refused *before* the
 * round trip, or a mistake in a config field is indistinguishable from a server that is
 * switched off.
 */
class HaNodesTest {

    private val ha = FakeHomeAssistant()
    private val context = DefaultExecutionContext(
        systemServices = RecordingSystemServices(),
        homeAssistant = ha,
    )

    private val service = HaServiceAction()
    private val value = HaStateValue()

    private fun ref(id: String, name: String = id, hub: String = "hub-1") =
        HomeAssistantRef.format(hub, id, name)

    // ---- action.ha_service ----

    @Test
    fun `an unconfigured service never reaches the hub`() = runBlocking {
        val out = service.execute(HaServiceConfig(service = ""), context)

        assertFalse(out.value.called)
        assertEquals("No service chosen", out.value.error)
        assertTrue(ha.calls.isEmpty())
    }

    /**
     * Names the string it read rather than only that it failed: a spec that arrived
     * through a variable or a text transform is exactly the case where seeing it is what
     * explains the failure.
     */
    @Test
    fun `a service reference that will not parse is named back`() = runBlocking {
        val out = service.execute(HaServiceConfig(service = "light.turn_on"), context)

        assertFalse(out.value.called)
        assertTrue(out.value.error.contains("light.turn_on"))
        assertTrue(ha.calls.isEmpty())
    }

    /** A reference that parses but names no domain cannot be a service call. */
    @Test
    fun `a reference with no domain is refused before the network`() = runBlocking {
        val out = service.execute(HaServiceConfig(service = ref("restart")), context)

        assertFalse(out.value.called)
        assertTrue(out.value.error.contains("light.turn_on"))
        assertTrue(ha.calls.isEmpty())
    }

    @Test
    fun `the service and its target reach the facade whole`() = runBlocking {
        val out = service.execute(
            HaServiceConfig(
                service = ref("climate.set_temperature", "Set target temperature"),
                target = ref("climate.hall"),
                data = """{"temperature": 21}""",
            ),
            context,
        )

        val sent = ha.calls.single()
        assertEquals("hub-1", sent.hubId)
        assertEquals("climate", sent.domain)
        assertEquals("set_temperature", sent.service)
        assertEquals("climate.hall", sent.targetEntityId)
        assertEquals("""{"temperature": 21}""", sent.data)
        assertTrue(out.value.called)
    }

    /**
     * The state two pickers make representable and one reference cannot prevent. Reported
     * rather than sent, because a call aimed at the wrong instance would either fail
     * obscurely or — worse — succeed against an entity of the same name.
     */
    @Test
    fun `an entity on a different hub than the service is refused`() = runBlocking {
        val out = service.execute(
            HaServiceConfig(
                service = ref("light.turn_on", hub = "hub-1"),
                target = ref("light.kitchen", hub = "hub-2"),
            ),
            context,
        )

        assertFalse(out.value.called)
        assertTrue(out.value.error.contains("different Home Assistant"))
        assertTrue(ha.calls.isEmpty())
    }

    /**
     * Unlike every other `@Picker` field in the app, blank is legal here:
     * `homeassistant.restart` and a script call take no entity at all.
     */
    @Test
    fun `a service with no target is still called`() = runBlocking {
        val out = service.execute(HaServiceConfig(service = ref("homeassistant.restart")), context)

        assertTrue(out.value.called)
        assertEquals("", ha.calls.single().targetEntityId)
    }

    @Test
    fun `a hub failure lands on the port rather than throwing`() = runBlocking {
        ha.callFailure = "Home Assistant refused the token"

        val out = service.execute(HaServiceConfig(service = ref("light.turn_on")), context)

        assertFalse(out.value.called)
        assertEquals("Home Assistant refused the token", out.value.error)
    }

    // ---- value.ha_state ----

    @Test
    fun `a value reads the entity out of the cache`() = runBlocking {
        ha.put("hub-1", "sensor.hall", state = "21.4", unit = "°C")

        assertEquals("21.4", value.read(HaStateValueConfig(entity = ref("sensor.hall")), context))
    }

    /**
     * The degradation the whole pull-side argument rests on: the consumer falls back to
     * its own form value and a comparison fails closed.
     */
    @Test
    fun `a value answers null when it cannot read`() = runBlocking {
        // Never chosen.
        assertNull(value.read(HaStateValueConfig(entity = ""), context))
        // A reference that will not parse.
        assertNull(value.read(HaStateValueConfig(entity = "sensor.hall"), context))
        // A hub reference naming no entity.
        assertNull(value.read(HaStateValueConfig(entity = HomeAssistantRef.formatHub("hub-1", "HA")), context))
        // An entity the cache has never seen — a connection that has not seeded.
        assertNull(value.read(HaStateValueConfig(entity = ref("sensor.missing")), context))
    }

    @Test
    fun `a value can read one attribute instead of the state`() = runBlocking {
        ha.put("hub-1", "light.desk", state = "on", attributes = """{"brightness": 128, "name": "Desk"}""")

        val config = HaStateValueConfig(entity = ref("light.desk"), attribute = "brightness")
        assertEquals("128", value.read(config, context))
        // A string attribute comes back unquoted, so a comparison works on it directly.
        assertEquals("Desk", value.read(config.copy(attribute = "name"), context))
    }

    /**
     * Null rather than blank, so a missing attribute degrades the way a missing entity
     * does instead of comparing equal to an empty string.
     */
    @Test
    fun `an attribute the entity does not have answers null`() = runBlocking {
        ha.put("hub-1", "light.desk", state = "on", attributes = """{"brightness": 128}""")

        assertNull(value.read(HaStateValueConfig(entity = ref("light.desk"), attribute = "colour"), context))
    }

    // ---- trigger.ha_state filters ----

    private fun change(state: String, previous: String) = HaStateChange(
        entityId = "binary_sensor.front_door",
        state = state,
        previousState = previous,
        changedAt = DateTime(0),
    )

    /**
     * The default that decides whether the node is usable. Home Assistant fires
     * `state_changed` for attribute-only changes too, so without this "when the porch
     * light comes on" fires every time anything about that light moves.
     */
    @Test
    fun `an attribute-only change is dropped by default and kept when asked for`() {
        val unchanged = change(state = "on", previous = "on")

        assertFalse(matches(HaStateTriggerConfig(), unchanged))
        assertTrue(matches(HaStateTriggerConfig(includeAttributeChanges = true), unchanged))
    }

    @Test
    fun `a blank filter matches any transition`() {
        assertTrue(matches(HaStateTriggerConfig(), change(state = "on", previous = "off")))
    }

    @Test
    fun `the to and from filters are matched case-insensitively`() {
        val opened = change(state = "on", previous = "off")

        assertTrue(matches(HaStateTriggerConfig(toState = "ON"), opened))
        assertTrue(matches(HaStateTriggerConfig(fromState = " off "), opened))
        assertFalse(matches(HaStateTriggerConfig(toState = "off"), opened))
        assertFalse(matches(HaStateTriggerConfig(fromState = "on"), opened))
    }

    // ---- trigger.ha_event filters ----

    @Test
    fun `an event filter is a substring test over the payload`() {
        val event = HaEvent(
            eventType = "zha_event",
            data = """{"command":"double_press","device_ieee":"00:11"}""",
            firedAt = DateTime(0),
        )

        assertTrue(matchesEvent(HaEventTriggerConfig(), event))
        assertTrue(matchesEvent(HaEventTriggerConfig(dataContains = "double_press"), event))
        assertTrue(matchesEvent(HaEventTriggerConfig(dataContains = "DOUBLE_PRESS"), event))
        assertFalse(matchesEvent(HaEventTriggerConfig(dataContains = "single_press"), event))
    }
}
