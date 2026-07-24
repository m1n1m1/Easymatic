package com.example.ottomatic.domain.registry

import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.engine.trigger.GeofenceTransition
import com.example.ottomatic.engine.trigger.GeofenceTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        assertEquals("out", exec.first().name)
        assertEquals("exactly one DATA output port named 'event'", 1, dataOut.size)
        assertEquals("event", dataOut.first().name)
        assertTrue("triggers must not expose data inputs", dataIn.isEmpty())
    }

    @Test
    fun `config schema declares the expected fields and defaults`() {
        val schema = ConfigSchemaRegistry.byId(GeofenceTrigger.TYPE_ID)
        assertNotNull("trigger.geofence must have a config schema", schema)
        val byKey = schema!!.fields.associateBy { it.key }
        assertNotNull(byKey["latitude"])
        assertNotNull(byKey["longitude"])
        assertNotNull(byKey["radiusMeters"])
        assertNotNull(byKey["event"])
        assertNotNull(byKey["dwellDelayMs"])
        assertEquals("100", byKey["radiusMeters"]?.defaultValue)
        assertEquals("enter", byKey["event"]?.defaultValue)
        assertEquals("30000", byKey["dwellDelayMs"]?.defaultValue)
    }

    @Test
    fun `event port schema is derived from GeofenceEvent`() {
        val def = NodeTypeRegistry.byId(GeofenceTrigger.TYPE_ID)!!
        val eventPort = def.ports.first { it.name == "event" }
        val schema = eventPort.schema as ItemSchema.Object
        assertEquals(
            setOf("triggerNodeId", "transition", "latitude", "longitude", "accuracyMeters", "timestamp"),
            schema.fields.keys,
        )
    }

    @Test
    fun `GeofenceTransition parse handles single comma and whitespace`() {
        assertEquals(setOf(GeofenceTransition.ENTER), GeofenceTransition.parse("enter"))
        assertEquals(setOf(GeofenceTransition.ENTER, GeofenceTransition.EXIT), GeofenceTransition.parse("enter,exit"))
        assertEquals(
            setOf(GeofenceTransition.ENTER, GeofenceTransition.EXIT, GeofenceTransition.DWELL),
            GeofenceTransition.parse("enter, exit , dwell"),
        )
    }

    @Test
    fun `GeofenceTransition parse falls back to enter for blank or unknown input`() {
        assertEquals(setOf(GeofenceTransition.ENTER), GeofenceTransition.parse(null))
        assertEquals(setOf(GeofenceTransition.ENTER), GeofenceTransition.parse(""))
        assertEquals(setOf(GeofenceTransition.ENTER), GeofenceTransition.parse("garbage"))
    }
}
