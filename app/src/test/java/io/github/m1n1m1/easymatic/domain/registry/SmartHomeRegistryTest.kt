package io.github.m1n1m1.easymatic.domain.registry

import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.core.model.PortName
import io.github.m1n1m1.easymatic.domain.model.Direction
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeKind
import io.github.m1n1m1.easymatic.domain.model.PortKind
import io.github.m1n1m1.easymatic.domain.model.config.PickerKind
import io.github.m1n1m1.easymatic.domain.model.schema.ItemSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The light nodes as the registry sees them, on `MailRegistryTest`'s template.
 */
class SmartHomeRegistryTest {

    private val control = NodeTypeId("action.light_control")
    private val scene = NodeTypeId("action.light_scene")
    private val state = NodeTypeId("action.light_state")

    private val all = listOf(control, scene, state)

    private fun type(id: NodeTypeId) = NodeTypeRegistry.byId(id)

    private fun fields(id: NodeTypeId) = ConfigSchemaRegistry.byId(id)!!.fields

    /**
     * The ids are pinned because a workflow persists them and an older schema is
     * discarded rather than migrated — renaming one later is unrecoverable for
     * anybody who used it. They deliberately say `light` rather than `hue`: nothing
     * above `data/hue/` is vendor-specific, so a second vendor's bulb would work
     * through these nodes under a name that lied.
     */
    @Test
    fun `all three are registered under the ids workflows store`() {
        all.forEach { id ->
            assertNotNull(id.value, ActionRegistry.byId(id))
            assertEquals(id.value, NodeKind.ACTION, type(id)?.kind)
            assertEquals(id.value, NodeCategory.SMART_HOME, type(id)?.category)
        }
    }

    /**
     * The reason there are three nodes and not one, stated as an assertion: the
     * declared ports differ. A node has exactly one DATA output, so the read cannot
     * be an operation on the control node, and a property may carry only one
     * `@Picker`, so the scene cannot be either.
     */
    @Test
    fun `each answers on its own data port with a struct of its own`() {
        all.forEach { id ->
            val out = type(id)!!.ports.filter { it.kind == PortKind.DATA && it.direction == Direction.OUT }
            assertEquals(id.value, 1, out.size)
            assertEquals(id.value, PortName("state"), out.single().name)
            assertTrue(id.value, out.single().schema is ItemSchema.Object)
        }
    }

    @Test
    fun `a light and a scene are chosen from two different choosers`() {
        assertEquals(
            ConfigFieldType.PICKER(PickerKind.LIGHT_TARGET),
            fields(control).single { it.key == ConfigKey("target") }.type,
        )
        assertEquals(
            ConfigFieldType.PICKER(PickerKind.LIGHT_TARGET),
            fields(state).single { it.key == ConfigKey("target") }.type,
        )
        assertEquals(
            ConfigFieldType.PICKER(PickerKind.LIGHT_SCENE),
            fields(scene).single { it.key == ConfigKey("scene") }.type,
        )
    }

    /**
     * One node rather than six, which is what `@VisibleWhen` buys: each value field
     * exists only for the operation that reads it.
     */
    @Test
    fun `each value field is shown only for the operation that uses it`() {
        mapOf(
            "brightness" to "SET_BRIGHTNESS",
            "colour" to "SET_COLOUR",
            "kelvin" to "SET_TEMPERATURE",
        ).forEach { (key, option) ->
            val rule = fields(control).single { it.key == ConfigKey(key) }.visibleWhen
            assertNotNull(key, rule)
            assertEquals(key, ConfigKey("op"), rule?.key)
            assertEquals(key, setOf(option), rule?.values)
        }
    }

    /**
     * "Only lights already on" is offered for all three value-setting operations and
     * for none of the switching ones, where it would mean nothing — an explicit
     * turn-on that skipped a light for being off would do nothing at all.
     */
    @Test
    fun `leaving switched-off lights alone is offered wherever it means something`() {
        val rule = fields(control).single { it.key == ConfigKey("onlyLightsOn") }.visibleWhen

        assertNotNull(rule)
        assertEquals(ConfigKey("op"), rule?.key)
        assertEquals(setOf("SET_BRIGHTNESS", "SET_COLOUR", "SET_TEMPERATURE"), rule?.values)
    }

    /**
     * The scene node takes an operation too, and — unlike Control Light — no field
     * comes with it. All three act on the one thing chosen: the room a turn-off
     * switches is found *from* the scene, so asking for it would be asking for
     * something the answer already contains.
     */
    @Test
    fun `the scene node offers its operations with no extra field behind them`() {
        val op = fields(scene).single { it.key == ConfigKey("op") }.type as ConfigFieldType.ENUM

        assertEquals(setOf("ACTIVATE", "TURN_OFF", "TOGGLE"), op.options.map { it.value }.toSet())
        assertTrue(fields(scene).all { it.visibleWhen == null })
    }

    /**
     * The deliberate absence, pinned so it cannot be "fixed" later. INTERNET is
     * install-time, is already in the manifest, and gets no `Permissions` constant —
     * the rule the manifest states for `android.permission.NFC`. What actually stops
     * a light node is a bridge that is unplugged or a key it has forgotten, neither
     * of which is a permission, and both of which are reported on the hub.
     */
    @Test
    fun `no light node declares a permission`() {
        all.forEach { id ->
            assertTrue(id.value, type(id)!!.permissionRequirements.isEmpty())
        }
    }

    /**
     * Palette search is the only thing that makes generic ids discoverable, and it
     * searches the description. Drop the vendor's name from it and "hue" finds
     * nothing.
     */
    @Test
    fun `each description names the vendor so palette search still finds it`() {
        all.forEach { id ->
            assertTrue(id.value, type(id)!!.description.contains("Hue"))
        }
    }

    /** Percent brightness and kelvin, which is what a person means. */
    @Test
    fun `the defaults are the units the form promises`() {
        val schema = ConfigSchemaRegistry.byId(control)!!
        assertEquals("100", schema.fields.single { it.key == ConfigKey("brightness") }.defaultValue)
        assertEquals("2700", schema.fields.single { it.key == ConfigKey("kelvin") }.defaultValue)
        assertEquals("400", schema.fields.single { it.key == ConfigKey("transitionMs") }.defaultValue)
    }
}
