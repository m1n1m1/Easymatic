package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.engine.trigger.DeviceMotionTrigger
import com.example.ottomatic.engine.trigger.DeviceOrientationTrigger
import com.example.ottomatic.engine.trigger.DeviceTapTrigger
import com.example.ottomatic.engine.trigger.LightLevelTrigger
import com.example.ottomatic.engine.trigger.ProximityTrigger
import com.example.ottomatic.engine.trigger.ShakeTrigger
import com.example.ottomatic.engine.trigger.gesture.TapCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Registry and schema contract for the accelerometer gesture triggers.
 *
 * `NodeDeclarationContractTest` already enforces the generic rules (port shape,
 * decodable defaults, visibility rules resolving); this covers what is specific
 * to these nodes.
 */
class GestureTriggerRegistryTest {

    private val gestureTriggers = listOf(
        ShakeTrigger.TYPE_ID,
        DeviceTapTrigger.TYPE_ID,
        DeviceMotionTrigger.TYPE_ID,
        ProximityTrigger.TYPE_ID,
        LightLevelTrigger.TYPE_ID,
    )

    @Test
    fun `every gesture trigger is registered and grouped under Sensors`() {
        gestureTriggers.forEach { typeId ->
            assertNotNull("$typeId must be in TriggerRegistry", TriggerRegistry.byId(typeId))
            val definition = NodeTypeRegistry.byId(typeId)
            assertNotNull("$typeId must be in NodeTypeRegistry", definition)
            assertEquals("$typeId category", NodeCategory.SENSORS, definition!!.category)
        }
    }

    @Test
    fun `every gesture trigger emits a SensorReading on a reading port`() {
        gestureTriggers.forEach { typeId ->
            val definition = NodeTypeRegistry.byId(typeId)!!
            val dataOut = definition.ports.filter { it.kind == PortKind.DATA && it.direction == Direction.OUT }
            assertEquals("$typeId data outputs", 1, dataOut.size)
            assertEquals("$typeId output name", PortName("reading"), dataOut.single().name)
            val schema = dataOut.single().schema as ItemSchema.Object
            assertEquals(
                "$typeId reading schema",
                setOf("event", "sensor", "value", "detail", "timestamp"),
                schema.fields.keys,
            )
        }
    }

    @Test
    fun `no gesture trigger needs a permission`() {
        // The accelerometer is free to read. That is what makes these usable
        // without sending the user to a settings page first.
        gestureTriggers.forEach { typeId ->
            assertTrue(
                "$typeId should declare no permissions",
                NodeTypeRegistry.byId(typeId)!!.permissionRequirements.isEmpty(),
            )
        }
    }

    @Test
    fun `tap offers only double and triple`() {
        // Single tap is undetectable from setting the phone down at any usable
        // false-positive rate, so it is deliberately absent.
        val field = fieldOf(DeviceTapTrigger.TYPE_ID, "taps")
        val options = (field.type as ConfigFieldType.ENUM).options.map { it.value }
        assertEquals(TapCount.entries.map { it.name }, options)
        assertEquals(TapCount.DOUBLE_TAP.name, field.defaultValue)
    }

    @Test
    fun `the flat requirement is only shown for put down`() {
        // It is meaningless for a pick-up, and significant motion has no
        // resting position at all.
        val rule = fieldOf(DeviceMotionTrigger.TYPE_ID, "surfaceOnly").visibleWhen
        assertEquals(VisibilityRule(ConfigKey("event"), setOf("PUT_DOWN")), rule)
    }

    @Test
    fun `movement sensitivity is tunable but hidden for significant motion`() {
        // That mode is decided in the sensor hub, so there is nothing to tune.
        val field = fieldOf(DeviceMotionTrigger.TYPE_ID, "sensitivity")
        assertEquals("MEDIUM", field.defaultValue)
        assertEquals(
            VisibilityRule(ConfigKey("event"), setOf("PICKED_UP", "PUT_DOWN")),
            field.visibleWhen,
        )
    }

    @Test
    fun `put down requires a flat surface by default`() {
        // Without it, "put down" means "stopped moving", which is true whenever
        // the user stands still holding the phone.
        assertEquals("true", fieldOf(DeviceMotionTrigger.TYPE_ID, "surfaceOnly").defaultValue)
    }

    @Test
    fun `screen-off sensing is off by default everywhere`() {
        // It costs battery, so nothing may opt the user in silently.
        listOf(ShakeTrigger.TYPE_ID, DeviceMotionTrigger.TYPE_ID).forEach { typeId ->
            assertEquals("$typeId screenOff", "NEVER", fieldOf(typeId, "screenOff").defaultValue)
        }
        assertEquals("NEVER", fieldOf(DeviceTapTrigger.TYPE_ID, "screenOff").defaultValue)
    }

    @Test
    fun `tap and orientation do not offer the significant-motion gate`() {
        // Significant motion is specified not to fire on taps, and is tuned for
        // travel rather than a single flip — so for both of these the option
        // would look like it worked while frequently doing nothing.
        listOf(DeviceTapTrigger.TYPE_ID, DeviceOrientationTrigger.TYPE_ID).forEach { typeId ->
            val options = (fieldOf(typeId, "screenOff").type as ConfigFieldType.ENUM)
                .options.map { it.value }
            assertEquals("$typeId screenOff options", listOf("NEVER", "ALWAYS"), options)
        }
    }

    @Test
    fun `shake and movement still offer it`() {
        // Both do follow general movement, so the low-power gate is genuinely
        // useful there and must not be removed along with the others.
        listOf(ShakeTrigger.TYPE_ID, DeviceMotionTrigger.TYPE_ID).forEach { typeId ->
            val options = (fieldOf(typeId, "screenOff").type as ConfigFieldType.ENUM)
                .options.map { it.value }
            assertEquals("$typeId screenOff options", listOf("NEVER", "WHEN_MOVING", "ALWAYS"), options)
        }
    }

    @Test
    fun `screen-off sensing is hidden for significant motion`() {
        // That mode is already a wake-up sensor, so the choice would be a no-op.
        assertEquals(
            VisibilityRule(ConfigKey("event"), setOf("PICKED_UP", "PUT_DOWN")),
            fieldOf(DeviceMotionTrigger.TYPE_ID, "screenOff").visibleWhen,
        )
    }

    private fun fieldOf(typeId: NodeTypeId, key: String): ConfigField<*> =
        ConfigSchemaRegistry.byId(typeId)!!.fields.first { it.key == ConfigKey(key) }
}
