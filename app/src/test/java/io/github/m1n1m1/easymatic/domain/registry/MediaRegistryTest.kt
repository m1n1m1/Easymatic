package io.github.m1n1m1.easymatic.domain.registry

import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.core.model.PortName
import io.github.m1n1m1.easymatic.core.permissions.PrerequisiteType
import io.github.m1n1m1.easymatic.domain.model.Direction
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.NodeKind
import io.github.m1n1m1.easymatic.domain.model.PortKind
import io.github.m1n1m1.easymatic.domain.model.ValueSource
import io.github.m1n1m1.easymatic.feature.permissions.rationaleRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The five media nodes' wiring, and the declarations that would otherwise degrade in
 * silence.
 *
 * The load-bearing one is the **permission**, which every node in this family needs and
 * which is not a compile error to get wrong in either direction: an undeclared grant leaves
 * a node reading null for ever with nothing in the Problems panel saying why, and a
 * `rationaleKey` absent from `PermissionCopy.rationaleFor` renders no card at all on the
 * node's config panel rather than failing the build.
 *
 * The second is that both **values are offered as `val:` sources**. That is what makes "if
 * music is playing" a comparison with no edge drawn, and losing it would not break anything
 * that would fail here otherwise — the nodes would simply stop appearing in a dropdown.
 */
class MediaRegistryTest {

    private val trigger = NodeTypeId("trigger.media_playback")
    private val control = NodeTypeId("action.media_control")
    private val seek = NodeTypeId("action.media_seek")
    private val playing = NodeTypeId("value.media_playing")
    private val nowPlaying = NodeTypeId("value.now_playing")

    private val all = listOf(trigger, control, seek, playing, nowPlaying)

    @Test
    fun `all five are registered`() {
        all.forEach { assertNotNull("$it must reach NodeTypeRegistry", NodeTypeRegistry.byId(it)) }
        assertNotNull(TriggerRegistry.byId(trigger))
        listOf(control, seek).forEach { assertNotNull(ActionRegistry.byId(it)) }
        listOf(playing, nowPlaying).forEach { assertNotNull(ValueRegistry.byId(it)) }
    }

    @Test
    fun `kinds and categories agree`() {
        val triggerDef = NodeTypeRegistry.byId(trigger)!!
        assertEquals(NodeKind.TRIGGER, triggerDef.kind)
        assertEquals(NodeCategory.PHONE_MEDIA, triggerDef.category)

        listOf(control, seek).forEach {
            val def = NodeTypeRegistry.byId(it)!!
            assertEquals(NodeKind.ACTION, def.kind)
            assertEquals(
                "$it belongs under Media, not Device Settings or Audio",
                NodeCategory.MEDIA,
                def.category,
            )
        }

        listOf(playing, nowPlaying).forEach {
            val def = NodeTypeRegistry.byId(it)!!
            assertEquals(NodeKind.VALUE, def.kind)
            assertEquals(NodeCategory.VALUE_DEVICE, def.category)
        }
    }

    /**
     * The seek node wears the one icon nobody else in the family does.
     *
     * Not decoration: `NodeIcon.FAST_FORWARD` exists only for this node, so a change that
     * silently reused `MUSIC` would leave a member of the enum with no node behind it and
     * `EditorColors` drawing something nothing asks for.
     */
    @Test
    fun `seek is the one node with its own icon`() {
        assertEquals(NodeIcon.FAST_FORWARD, NodeTypeRegistry.byId(seek)!!.icon)
        listOf(trigger, control, playing, nowPlaying).forEach {
            assertEquals(NodeIcon.MUSIC, NodeTypeRegistry.byId(it)!!.icon)
        }
    }

    @Test
    fun `every node declares notification access, and its rationale has copy`() {
        all.forEach { id ->
            val requirements = NodeTypeRegistry.byId(id)!!.permissionRequirements
            val requirement = requirements.singleOrNull()
            assertNotNull("$id must declare exactly one prerequisite", requirement)
            assertEquals(PrerequisiteType.NOTIFICATION_LISTENER, requirement!!.type)
            assertEquals(
                "$id must not also name a manifest permission: the grant is a Settings page",
                null,
                requirement.manifestPermission,
            )
            assertEquals("media.playback", requirement.rationaleKey)
            assertNotNull(
                "$id would render no rationale card at all, and would not fail the build",
                rationaleRes(requirement),
            )
        }
    }

    /**
     * The values keep the purity contract, and the trigger carries its projection.
     *
     * `NodeDeclarationRulesTest` already pins "no exec ports on a value" across every node,
     * so what is here is the part specific to this family: the derived `title` output, which
     * is what saves an `action.break` in front of every notification.
     */
    @Test
    fun `port shapes are what the graph is wired against`() {
        val triggerDef = NodeTypeRegistry.byId(trigger)!!
        val outputs = triggerDef.outputs(PortKind.DATA).map { it.name }
        assertEquals(listOf(PortName("playback"), PortName("title")), outputs)

        listOf(playing, nowPlaying).forEach { id ->
            val def = NodeTypeRegistry.byId(id)!!
            assertTrue("$id must have no execution ports", def.ports.none { it.kind == PortKind.EXECUTION })
            assertTrue(
                "$id must have no data inputs",
                def.ports.none { it.kind == PortKind.DATA && it.direction == Direction.IN },
            )
            assertEquals(1, def.outputs(PortKind.DATA).size)
        }
    }

    /**
     * Both values stay `val:` sources, which is most of why the boolean one exists.
     *
     * `sourceOptions()` excludes any value whose answer depends on config, because a `val:`
     * read is performed with no config at all. Asserting on the *config* rather than on the
     * dropdown is what makes this fail on the day somebody adds an app field to
     * `value.now_playing` — exactly when "if music is playing" would quietly stop being one
     * comparison.
     */
    @Test
    fun `neither value takes config, so both stay action_if sources`() {
        listOf(playing, nowPlaying).forEach { id ->
            val schema = ConfigSchemaRegistry.byId(id)
            assertTrue(
                "$id must stay config-free or it drops out of action.if's sources",
                schema == null || schema.fields.isEmpty(),
            )
            assertEquals("val:" + id.value, ValueSource.valueSpec(id))
        }
    }
}
