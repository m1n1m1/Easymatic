package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.engine.trigger.DeviceOrientationTrigger
import com.example.ottomatic.engine.trigger.gesture.DeviceOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies `trigger.device_orientation` — the first sensor-backed trigger — is
 * wired into every central registry and matches the contract the engine and UI
 * rely on.
 */
class DeviceOrientationRegistryTest {

    @Test
    fun `the trigger is registered with the correct typeId`() {
        val trigger = TriggerRegistry.byId(DeviceOrientationTrigger.TYPE_ID)
        assertNotNull("trigger.device_orientation must be registered in TriggerRegistry", trigger)
        assertEquals(DeviceOrientationTrigger.TYPE_ID, trigger!!.typeId)
    }

    @Test
    fun `it is a trigger exposing one exec out and one typed data out`() {
        val definition = NodeTypeRegistry.byId(DeviceOrientationTrigger.TYPE_ID)
        assertNotNull(definition)
        assertEquals(NodeKind.TRIGGER, definition!!.kind)
        assertEquals(NodeCategory.SENSORS, definition.category)

        val exec = definition.ports.filter { it.kind == PortKind.EXECUTION }
        val dataOut = definition.ports.filter { it.kind == PortKind.DATA && it.direction == Direction.OUT }
        val dataIn = definition.ports.filter { it.kind == PortKind.DATA && it.direction == Direction.IN }
        assertEquals("exactly one EXECUTION port", 1, exec.size)
        assertEquals(PortName("out"), exec.single().name)
        assertEquals("exactly one DATA output named 'reading'", 1, dataOut.size)
        assertEquals(PortName("reading"), dataOut.single().name)
        assertTrue("triggers must not expose data inputs", dataIn.isEmpty())
    }

    @Test
    fun `the reading port schema is derived from SensorReading`() {
        val definition = NodeTypeRegistry.byId(DeviceOrientationTrigger.TYPE_ID)!!
        val schema = definition.port(PortName("reading"))!!.schema as ItemSchema.Object
        assertEquals(setOf("event", "sensor", "value", "detail", "timestamp"), schema.fields.keys)
    }

    @Test
    fun `the magnitude is a number so a comparison needs no conversion node`() {
        // If `value` were text, "darker than 5 lux" would need a
        // transform.convert dropped into the wire before every action.if.
        val definition = NodeTypeRegistry.byId(DeviceOrientationTrigger.TYPE_ID)!!
        val schema = definition.port(PortName("reading"))!!.schema as ItemSchema.Object
        val value = schema.fields.getValue("value") as ItemSchema.Primitive
        assertEquals(Float::class, value.kClass)
    }

    @Test
    fun `the orientation filter offers every resting position plus a blank Any`() {
        val schema = ConfigSchemaRegistry.byId(DeviceOrientationTrigger.TYPE_ID)
        assertNotNull(schema)
        val field = schema!!.fields.first { it.key == ConfigKey("orientation") }
        val options = (field.type as ConfigFieldType.ENUM).options

        // Unset means "any change", which is what lets one node replace the
        // five or six separate triggers other automation apps ship.
        assertEquals("", field.defaultValue)
        assertEquals(
            listOf("") + DeviceOrientation.entries.map { it.name },
            options.map { it.value },
        )
    }

    @Test
    fun `the dwell is configurable and defaults to 700ms`() {
        val schema = ConfigSchemaRegistry.byId(DeviceOrientationTrigger.TYPE_ID)!!
        val field = schema.fields.first { it.key == ConfigKey("dwellMs") }
        assertEquals(ConfigFieldType.INT, field.type)
        assertEquals("700", field.defaultValue)
    }

    @Test
    fun `it declares no permissions`() {
        // The accelerometer needs none, which is most of the point of this node.
        val definition = NodeTypeRegistry.byId(DeviceOrientationTrigger.TYPE_ID)!!
        assertTrue(definition.permissionRequirements.isEmpty())
    }
}
