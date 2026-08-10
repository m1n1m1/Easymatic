package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.config.PickerKind
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.engine.trigger.GeofenceConfig
import com.example.ottomatic.engine.trigger.GeofenceTransition
import com.example.ottomatic.engine.trigger.GeofenceTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the `trigger.geofence` trigger is wired into every central registry
 * and that its node type / config schema match the contract the engine and UI
 * rely on.
 */
class GeofenceRegistryTest {

    @Test
    fun `trigger geofence is registered with the correct typeId`() {
        val trigger = TriggerRegistry.byId(GeofenceTrigger.TYPE_ID)
        assertNotNull("trigger.geofence must be registered in TriggerRegistry", trigger)
        assertEquals(GeofenceTrigger.TYPE_ID, trigger!!.typeId)
    }

    @Test
    fun `node type definition is a trigger exposing one exec out and one typed data out`() {
        val def = NodeTypeRegistry.byId(GeofenceTrigger.TYPE_ID)
        assertNotNull("trigger.geofence must be registered in NodeTypeRegistry", def)
        assertEquals(NodeKind.TRIGGER, def!!.kind)
        val exec = def.ports.filter { it.kind == PortKind.EXECUTION }
        val dataOut = def.ports.filter { it.kind == PortKind.DATA && it.direction == Direction.OUT }
        val dataIn = def.ports.filter { it.kind == PortKind.DATA && it.direction == Direction.IN }
        assertEquals("exactly one EXECUTION port", 1, exec.size)
        assertEquals(PortName("out"), exec.first().name)
        assertEquals("exactly one DATA output port named 'event'", 1, dataOut.size)
        assertEquals(PortName("event"), dataOut.first().name)
        assertTrue("triggers must not expose data inputs", dataIn.isEmpty())
    }

    @Test
    fun `config schema declares the expected fields and defaults`() {
        val schema = ConfigSchemaRegistry.byId(GeofenceTrigger.TYPE_ID)
        assertNotNull("trigger.geofence must have a config schema", schema)
        val byKey = schema!!.fields.associateBy { it.key }
        assertNotNull(byKey[ConfigKey("placeId")])
        assertNotNull(byKey[ConfigKey("dwellDelayMs")])
        assertEquals("30000", byKey[ConfigKey("dwellDelayMs")]?.defaultValue)
        assertEquals("false", byKey[ConfigKey("onAway")]?.defaultValue)
        // Minutes, not the milliseconds its dwell counterpart uses: an away
        // period is tens of minutes and a doze-batched alarm cannot do better.
        assertEquals("30", byKey[ConfigKey("awayMinutes")]?.defaultValue)
        // Coordinates and radius moved to the shared place library; a trigger
        // that still declared them would be storing a second copy that could
        // drift from the place it points at.
        assertNull(byKey[ConfigKey("latitude")])
        assertNull(byKey[ConfigKey("longitude")])
        assertNull(byKey[ConfigKey("radiusMeters")])
    }

    @Test
    fun `place is chosen with a picker rather than typed`() {
        val schema = ConfigSchemaRegistry.byId(GeofenceTrigger.TYPE_ID)!!
        val placeField = schema.fields.first { it.key == ConfigKey("placeId") }
        assertEquals(ConfigFieldType.PICKER(PickerKind.GEOFENCE_PLACE), placeField.type)
    }

    @Test
    fun `dwell delay is only shown when dwell is armed`() {
        val schema = ConfigSchemaRegistry.byId(GeofenceTrigger.TYPE_ID)!!
        val rule = schema.fields.first { it.key == ConfigKey("dwellDelayMs") }.visibleWhen
        assertEquals(VisibilityRule(ConfigKey("onDwell"), setOf("true")), rule)
    }

    @Test
    fun `the trigger declares the location permissions it cannot fire without`() {
        val def = NodeTypeRegistry.byId(GeofenceTrigger.TYPE_ID)!!
        val declared = def.permissionRequirements.mapNotNull { it.manifestPermission }.toSet()
        assertEquals(
            setOf(
                Permissions.ACCESS_FINE_LOCATION.manifest,
                Permissions.ACCESS_BACKGROUND_LOCATION.manifest,
            ),
            declared,
        )
    }

    @Test
    fun `away delay is only shown when the away countdown is armed`() {
        val schema = ConfigSchemaRegistry.byId(GeofenceTrigger.TYPE_ID)!!
        val rule = schema.fields.first { it.key == ConfigKey("awayMinutes") }.visibleWhen
        assertEquals(VisibilityRule(ConfigKey("onAway"), setOf("true")), rule)
    }

    @Test
    fun `transition switches derive the armed transition set`() {
        assertEquals(setOf(GeofenceTransition.ENTER), GeofenceConfig().platformTransitions)
        assertEquals(
            setOf(GeofenceTransition.ENTER, GeofenceTransition.EXIT),
            GeofenceConfig(onEnter = true, onExit = true).platformTransitions,
        )
        assertEquals(
            setOf(GeofenceTransition.ENTER, GeofenceTransition.EXIT, GeofenceTransition.DWELL),
            GeofenceConfig(onEnter = true, onExit = true, onDwell = true).platformTransitions,
        )
    }

    @Test
    fun `an empty transition selection still arms enter`() {
        assertEquals(
            setOf(GeofenceTransition.ENTER),
            GeofenceConfig(onEnter = false, onExit = false, onDwell = false).platformTransitions,
        )
    }

    /**
     * The away half is not a platform transition, so the two sets diverge: the
     * fence has to report enter and exit — one starts the countdown, the other
     * cancels it — while the node publishes neither.
     */
    @Test
    fun `arming only the away countdown still watches enter and exit`() {
        val awayOnly = GeofenceConfig(onEnter = false, onAway = true)
        assertEquals(
            setOf(GeofenceTransition.ENTER, GeofenceTransition.EXIT),
            awayOnly.platformTransitions,
        )
        assertEquals(setOf("away"), awayOnly.emittedEvents)
    }

    @Test
    fun `emitted events name exactly the switches that are on`() {
        assertEquals(setOf("enter"), GeofenceConfig().emittedEvents)
        assertEquals(
            setOf("enter", "exit", "dwell", "away"),
            GeofenceConfig(onEnter = true, onExit = true, onDwell = true, onAway = true).emittedEvents,
        )
        // Same fallback as the transition set: nothing chosen means enter.
        assertEquals(
            setOf("enter"),
            GeofenceConfig(onEnter = false, onExit = false, onDwell = false, onAway = false).emittedEvents,
        )
    }

    /**
     * What `ConfigFormHint` warns on. It listed the switch names itself once,
     * and adding a fourth left a node configured entirely correctly being told
     * it had chosen nothing.
     */
    @Test
    fun `choosing only the away countdown counts as having chosen something`() {
        assertTrue(GeofenceConfig(onEnter = false, onAway = true).hasChosenEvent)
        assertTrue(GeofenceConfig().hasChosenEvent)
        assertFalse(
            GeofenceConfig(onEnter = false, onExit = false, onDwell = false, onAway = false).hasChosenEvent,
        )
    }

    @Test
    fun `the away delay reaches the host in milliseconds and never as zero`() {
        assertEquals(30 * 60_000L, GeofenceConfig().awayDelayMs)
        assertEquals(90 * 60_000L, GeofenceConfig(awayMinutes = 90).awayDelayMs)
        // A countdown of zero would fire the alarm at the instant of the exit,
        // which is "on exit" wearing the wrong label.
        assertEquals(60_000L, GeofenceConfig(awayMinutes = 0).awayDelayMs)
    }

    @Test
    fun `event port schema is derived from GeofenceEvent`() {
        val def = NodeTypeRegistry.byId(GeofenceTrigger.TYPE_ID)!!
        val eventPort = def.ports.first { it.name == PortName("event") }
        val schema = eventPort.schema as ItemSchema.Object
        assertEquals(
            setOf("triggerNodeId", "transition", "latitude", "longitude", "accuracyMeters", "timestamp"),
            schema.fields.keys,
        )
    }
}
