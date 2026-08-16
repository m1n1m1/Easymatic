package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.ValueSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The image family's wiring into every central registry, and the four declarations that
 * would otherwise degrade in silence.
 *
 * The load-bearing assertions are the ones no global contract test can make: that every
 * image node declares the media read (a node that forgot it reads nothing for ever with
 * nothing saying why), that only the *write* nodes declare the overlay grant (declaring it
 * everywhere would badge working nodes), that `MANAGE_MEDIA` is declared by **no** node,
 * and that `value.latest_image` stays namable as an `action.if` source — which is most of
 * why the value node is worth having.
 */
class ImageRegistryTest {

    private val trigger = NodeTypeId("trigger.image_saved")
    private val value = NodeTypeId("value.latest_image")
    private val readActions = listOf("action.image_list", "action.image_info").map(::NodeTypeId)
    private val writeActions = listOf(
        "action.image_metadata",
        "action.image_move",
        "action.image_delete",
    ).map(::NodeTypeId)
    private val edit = NodeTypeId("action.image_edit")

    private val everyImageNode = listOf(trigger, value, edit) + readActions + writeActions

    @Test
    fun `every image node is registered`() {
        everyImageNode.forEach {
            assertNotNull("$it must reach NodeTypeRegistry", NodeTypeRegistry.byId(it))
        }
        assertNotNull(TriggerRegistry.byId(trigger))
        assertNotNull(ValueRegistry.byId(value))
        (readActions + writeActions + edit).forEach {
            assertNotNull("$it must reach ActionRegistry", ActionRegistry.byId(it))
        }
    }

    @Test
    fun `kinds and categories agree`() {
        assertEquals(NodeKind.TRIGGER, NodeTypeRegistry.byId(trigger)!!.kind)
        assertEquals(NodeCategory.PHONE_MEDIA, NodeTypeRegistry.byId(trigger)!!.category)
        assertEquals(NodeKind.VALUE, NodeTypeRegistry.byId(value)!!.kind)
        assertEquals(NodeCategory.VALUE_IMAGES, NodeTypeRegistry.byId(value)!!.category)
        (readActions + writeActions + edit).forEach {
            val def = NodeTypeRegistry.byId(it)!!
            assertEquals("$it is an action", NodeKind.ACTION, def.kind)
            assertEquals("$it belongs under Photos", NodeCategory.IMAGES, def.category)
        }
    }

    @Test
    fun `every image node declares the media read`() {
        everyImageNode.forEach { id ->
            val declared = NodeTypeRegistry.byId(id)!!.permissionRequirements
                .any { it.manifestPermission == Permissions.READ_MEDIA_IMAGES.manifest }
            assertTrue("$id must declare READ_MEDIA_IMAGES or it reads nothing, silently", declared)
        }
    }

    /**
     * Only the nodes that can be refused declare the overlay grant.
     *
     * `action.image_edit` is deliberately not among them: its output is always a *new*
     * file this app created, which needs no confirmation on any version — so declaring it
     * would put a permanent badge on a node that cannot fail that way.
     */
    @Test
    fun `only the write nodes declare the overlay grant`() {
        writeActions.forEach { id ->
            val declared = NodeTypeRegistry.byId(id)!!.permissionRequirements
                .any { it.type == PrerequisiteType.OVERLAY }
            assertTrue("$id can need Android's confirmation, so it must declare OVERLAY", declared)
        }
        (readActions + edit + trigger + value).forEach { id ->
            val declared = NodeTypeRegistry.byId(id)!!.permissionRequirements
                .any { it.type == PrerequisiteType.OVERLAY }
            assertFalse("$id cannot need confirmation, so OVERLAY would be a false badge", declared)
        }
    }

    /**
     * `MANAGE_MEDIA` is offered on the Permissions screen and required by nothing.
     *
     * Every image node works without it — Android asks the user instead — so a node
     * declaring it would show an amber badge for a node that is working, which is the
     * failure `value.nfc` and `usesContacts` both exist to avoid.
     */
    @Test
    fun `no node declares MANAGE_MEDIA`() {
        val declaring = NodeTypeRegistry.all
            .filter { def -> def.permissionRequirements.any { it.type == PrerequisiteType.MANAGE_MEDIA } }
            .map { it.typeId }
        assertEquals(
            "MANAGE_MEDIA is an app-level offer, never a node's prerequisite",
            emptyList<NodeTypeId>(),
            declaring,
        )

        val catalogued = PermissionCatalogue.entries()
            .any { it.requirement.type == PrerequisiteType.MANAGE_MEDIA }
        assertTrue("It must still be listed on the Permissions screen", catalogued)
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
     * The value node stays a `val:` source, which is most of why it exists.
     *
     * `sourceOptions()` excludes any value node whose answer depends on config, because a
     * `val:` read is performed with no config at all. Having none is therefore the whole
     * qualification — and asserting on *that* rather than on the dropdown's contents is
     * what makes this test fail on the day somebody adds a "folder" field here, which is
     * exactly when the node would quietly stop being comparable.
     */
    @Test
    fun `the latest picture takes no config, so it stays an action_if source`() {
        val schema = ConfigSchemaRegistry.byId(value)
        assertTrue(
            "value.latest_image must stay config-free or it drops out of action.if's sources",
            schema == null || schema.fields.isEmpty(),
        )
        assertEquals("val:${value.value}", ValueSource.valueSpec(value))
    }

    @Test
    fun `image_info names its output details rather than image`() {
        val def = NodeTypeRegistry.byId(NodeTypeId("action.image_info"))!!
        val dataOut = def.ports.single { it.kind == PortKind.DATA && it.direction == Direction.OUT }
        // Not `image`: the config field already claims that name, and the generated
        // string keys carry no direction, so the two would collide into one label.
        assertEquals(PortName("details"), dataOut.name)
    }
}
