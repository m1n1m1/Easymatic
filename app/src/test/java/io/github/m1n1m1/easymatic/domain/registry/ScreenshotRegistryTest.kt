package io.github.m1n1m1.easymatic.domain.registry

import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.core.model.PortName
import io.github.m1n1m1.easymatic.core.permissions.Permissions
import io.github.m1n1m1.easymatic.core.permissions.PrerequisiteType
import io.github.m1n1m1.easymatic.domain.model.Direction
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.NodeKind
import io.github.m1n1m1.easymatic.domain.model.PortKind
import io.github.m1n1m1.easymatic.domain.model.ValueSource
import io.github.m1n1m1.easymatic.feature.permissions.rationaleRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three screenshot nodes' wiring, and the declarations that would otherwise degrade in
 * silence.
 *
 * Their own file rather than lines in `ImageRegistryTest` because one of them breaks that
 * file's central rule on purpose: `action.screenshot` is the only node under Photos that
 * does **not** declare the media read, and the reason is worth stating where somebody
 * adding the grant "for consistency" will read it.
 */
class ScreenshotRegistryTest {

    private val trigger = NodeTypeId("trigger.screenshot")
    private val action = NodeTypeId("action.screenshot")
    private val value = NodeTypeId("value.latest_screenshot")

    @Test
    fun `all three are registered`() {
        listOf(trigger, action, value).forEach {
            assertNotNull("$it must reach NodeTypeRegistry", NodeTypeRegistry.byId(it))
        }
        assertNotNull(TriggerRegistry.byId(trigger))
        assertNotNull(ActionRegistry.byId(action))
        assertNotNull(ValueRegistry.byId(value))
    }

    @Test
    fun `kinds, categories and icon agree`() {
        val triggerDef = NodeTypeRegistry.byId(trigger)!!
        assertEquals(NodeKind.TRIGGER, triggerDef.kind)
        assertEquals(NodeCategory.PHONE_MEDIA, triggerDef.category)

        val actionDef = NodeTypeRegistry.byId(action)!!
        assertEquals(NodeKind.ACTION, actionDef.kind)
        assertEquals(NodeCategory.IMAGES, actionDef.category)

        val valueDef = NodeTypeRegistry.byId(value)!!
        assertEquals(NodeKind.VALUE, valueDef.kind)
        assertEquals(NodeCategory.VALUE_IMAGES, valueDef.category)

        listOf(triggerDef, actionDef, valueDef).forEach {
            assertEquals("${it.typeId} is about this screen, not a photograph", NodeIcon.SCREENSHOT, it.icon)
        }
    }

    /**
     * The action declares accessibility and **nothing else**.
     *
     * `READ_MEDIA_IMAGES` is deliberately absent: the node writes a row this app creates,
     * which needs no media grant on any version, and the screenshot-folder lookup falls
     * back to a sensible default without one. Declaring it would put a permanent amber
     * badge in the Problems panel on a node that works perfectly — the failure
     * `value.nfc` and `MANAGE_MEDIA` both exist to avoid.
     */
    @Test
    fun `the action declares accessibility only`() {
        val declared = NodeTypeRegistry.byId(action)!!.permissionRequirements
        assertEquals(1, declared.size)
        assertEquals(PrerequisiteType.ACCESSIBILITY_SERVICE, declared.single().type)
        assertNull(
            "a prerequisite type carries the grant, so there is no manifest name to declare",
            declared.single().manifestPermission,
        )
    }

    /**
     * Its rationale key resolves, because a missing one renders no card at all.
     *
     * That is the quiet failure `ADDING_NODES.md` warns about: nothing crashes, nothing
     * logs, the node's config panel simply never explains why it cannot work.
     */
    @Test
    fun `the action's rationale key has copy behind it`() {
        val requirement = NodeTypeRegistry.byId(action)!!.permissionRequirements.single()
        assertEquals("screen.capture", requirement.rationaleKey)
        assertNotNull(
            "an unmatched rationaleKey renders no card, silently",
            rationaleRes(requirement),
        )
    }

    @Test
    fun `the reading nodes declare the media read`() {
        listOf(trigger, value).forEach { id ->
            val declared = NodeTypeRegistry.byId(id)!!.permissionRequirements
                .any { it.manifestPermission == Permissions.READ_MEDIA_IMAGES.manifest }
            assertTrue("$id must declare READ_MEDIA_IMAGES or it reads nothing, silently", declared)
        }
    }

    @Test
    fun `the trigger has one exec out, no data inputs, and a path projection`() {
        val def = NodeTypeRegistry.byId(trigger)!!
        val exec = def.ports.filter { it.kind == PortKind.EXECUTION }
        val dataOut = def.ports.filter { it.kind == PortKind.DATA && it.direction == Direction.OUT }
        val dataIn = def.ports.filter { it.kind == PortKind.DATA && it.direction == Direction.IN }

        assertEquals(1, exec.size)
        assertTrue("a trigger may not take data inputs", dataIn.isEmpty())
        assertEquals(
            "the picture plus the path projection a sibling node takes",
            setOf(PortName("image"), PortName("path")),
            dataOut.map { it.name }.toSet(),
        )
    }

    /**
     * The trigger takes no config, which is what makes it different from
     * `trigger.image_saved` rather than a duplicate of it.
     *
     * A folder field here would hand back the very question the node exists to answer.
     */
    @Test
    fun `the trigger takes no config`() {
        val schema = ConfigSchemaRegistry.byId(trigger)
        assertTrue(schema == null || schema.fields.isEmpty())
    }

    /**
     * The value node stays a `val:` source, which is most of why it exists.
     *
     * `sourceOptions()` excludes any value whose answer depends on config, because a
     * `val:` read is performed with no config at all. Asserting on the config rather than
     * the dropdown is what makes this fail on the day somebody adds a field here — exactly
     * when the node would quietly stop being comparable in `action.if`.
     */
    @Test
    fun `the latest screenshot takes no config, so it stays an action_if source`() {
        val schema = ConfigSchemaRegistry.byId(value)
        assertTrue(
            "value.latest_screenshot must stay config-free or it drops out of action.if's sources",
            schema == null || schema.fields.isEmpty(),
        )
        assertEquals("val:${value.value}", ValueSource.valueSpec(value))
    }

    /**
     * The action cannot need Android's write confirmation, so it must not declare the
     * overlay grant — `action.image_edit`'s reasoning, for the same reason: everything it
     * creates is a row this app owns.
     */
    @Test
    fun `the action does not declare the overlay grant`() {
        val declared = NodeTypeRegistry.byId(action)!!.permissionRequirements
            .any { it.type == PrerequisiteType.OVERLAY }
        assertFalse("a row we create needs no confirmation, so OVERLAY would be a false badge", declared)
    }
}
