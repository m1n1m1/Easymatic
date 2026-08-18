package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.ValueSource
import com.example.ottomatic.feature.permissions.rationaleRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The five recording nodes' wiring, and the declarations that would otherwise degrade in
 * silence.
 *
 * Two of them are here mainly to pin what is **absent**. `trigger.recording_saved` and
 * `value.recording` do not declare the microphone, and somebody adding it "for consistency"
 * would put a permanent amber badge on two nodes that work perfectly without the grant —
 * `value.nfc`'s failure, one family along.
 */
class RecordingRegistryTest {

    private val trigger = NodeTypeId("trigger.recording_saved")
    private val record = NodeTypeId("action.record_audio")
    private val start = NodeTypeId("action.record_start")
    private val stop = NodeTypeId("action.record_stop")
    private val value = NodeTypeId("value.recording")

    private val all = listOf(trigger, record, start, stop, value)

    @Test
    fun `all five are registered`() {
        all.forEach { assertNotNull("$it must reach NodeTypeRegistry", NodeTypeRegistry.byId(it)) }
        assertNotNull(TriggerRegistry.byId(trigger))
        listOf(record, start, stop).forEach { assertNotNull(ActionRegistry.byId(it)) }
        assertNotNull(ValueRegistry.byId(value))
    }

    @Test
    fun `kinds, categories and icon agree`() {
        val triggerDef = NodeTypeRegistry.byId(trigger)!!
        assertEquals(NodeKind.TRIGGER, triggerDef.kind)
        assertEquals(NodeCategory.PHONE_MEDIA, triggerDef.category)

        listOf(record, start, stop).forEach {
            val def = NodeTypeRegistry.byId(it)!!
            assertEquals(NodeKind.ACTION, def.kind)
            assertEquals("$it belongs under Audio, not Files", NodeCategory.AUDIO, def.category)
        }

        val valueDef = NodeTypeRegistry.byId(value)!!
        assertEquals(NodeKind.VALUE, valueDef.kind)
        assertEquals(NodeCategory.VALUE_DEVICE, valueDef.category)

        all.forEach {
            val def = NodeTypeRegistry.byId(it)!!
            assertEquals("${def.typeId} listens, so it wears a microphone", NodeIcon.MICROPHONE, def.icon)
        }
    }

    @Test
    fun `the three actions declare the microphone and nothing else`() {
        listOf(record, start, stop).forEach { id ->
            val declared = NodeTypeRegistry.byId(id)!!.permissionRequirements
            assertEquals("$id needs exactly one grant", 1, declared.size)
            assertEquals(PrerequisiteType.RUNTIME, declared.single().type)
            assertEquals(Permissions.RECORD_AUDIO.manifest, declared.single().manifestPermission)
        }
    }

    /**
     * Its rationale key resolves, because a missing one renders no card at all.
     *
     * That is the quiet failure `ADDING_NODES.md` warns about: nothing crashes, nothing
     * logs, the node's config panel simply never explains why it cannot work.
     */
    @Test
    fun `the actions' rationale key has copy behind it`() {
        val requirement = NodeTypeRegistry.byId(record)!!.permissionRequirements.single()
        assertEquals("audio.record", requirement.rationaleKey)
        assertNotNull(
            "an unmatched rationaleKey renders no card, silently",
            rationaleRes(requirement),
        )
    }

    /**
     * The trigger and the value declare nothing, deliberately.
     *
     * The trigger is *told* that a recording finished rather than listening to anything, and
     * the value reads a flag this process already holds — both are correct with the grant
     * refused, so a badge on either would be the Problems panel raising an alarm about a
     * node that is working.
     */
    @Test
    fun `the trigger and the value declare no permission at all`() {
        listOf(trigger, value).forEach { id ->
            assertTrue(
                "$id works with the microphone refused, so declaring it would be a false badge",
                NodeTypeRegistry.byId(id)!!.permissionRequirements.isEmpty(),
            )
        }
    }

    @Test
    fun `the trigger has one exec out, no data inputs, and a path projection`() {
        val def = NodeTypeRegistry.byId(trigger)!!
        val exec = def.ports.filter { it.kind == PortKind.EXECUTION }
        val dataOut = def.ports.filter { it.kind == PortKind.DATA && it.direction == Direction.OUT }
        val dataIn = def.ports.filter { it.kind == PortKind.DATA && it.direction == Direction.IN }

        assertEquals(1, exec.size)
        assertEquals(Direction.OUT, exec.single().direction)
        assertTrue("a trigger may not take data inputs", dataIn.isEmpty())
        assertEquals(
            "the recording plus the path projection a sibling node takes",
            setOf(PortName("recording"), PortName("path")),
            dataOut.map { it.name }.toSet(),
        )
    }

    /**
     * `action.record_start` has no data output, and that is the family's whole shape.
     *
     * A receipt there could only carry a `changed` meaning "began", where the same field on
     * the other two means "finished". This fails on the day somebody adds one.
     */
    @Test
    fun `start reports nothing while the other two report a recording`() {
        val startOut = NodeTypeRegistry.byId(start)!!.ports
            .filter { it.kind == PortKind.DATA && it.direction == Direction.OUT }
        assertTrue("a recording that has not finished has nothing to report", startOut.isEmpty())

        listOf(record, stop).forEach { id ->
            val out = NodeTypeRegistry.byId(id)!!.ports
                .filter { it.kind == PortKind.DATA && it.direction == Direction.OUT }
            assertEquals(listOf(PortName("state")), out.map { it.name })
        }
    }

    /** There is one microphone, so there is nothing for a stop to choose between. */
    @Test
    fun `stopping takes no config`() {
        val schema = ConfigSchemaRegistry.byId(stop)
        assertTrue(schema == null || schema.fields.isEmpty())
    }

    @Test
    fun `the trigger takes no config`() {
        val schema = ConfigSchemaRegistry.byId(trigger)
        assertTrue(schema == null || schema.fields.isEmpty())
    }

    /**
     * The value node stays a `val:` source, which is most of why it exists.
     *
     * `sourceOptions()` excludes any value whose answer depends on config, because a `val:`
     * read is performed with no config at all. Asserting on the config rather than the
     * dropdown is what makes this fail on the day somebody adds a field here — exactly when
     * "if nothing is recording, start" would quietly stop being one comparison.
     */
    @Test
    fun `the value takes no config, so it stays an action_if source`() {
        val schema = ConfigSchemaRegistry.byId(value)
        assertTrue(
            "value.recording must stay config-free or it drops out of action.if's sources",
            schema == null || schema.fields.isEmpty(),
        )
        assertEquals("val:${value.value}", ValueSource.valueSpec(value))
    }

    /**
     * Nothing here asks for the overlay grant.
     *
     * A recording never needs the user to confirm anything mid-run — unlike an image write,
     * which may — so declaring it would be a badge nothing behind the node can justify.
     */
    @Test
    fun `no node in the family declares the overlay grant`() {
        all.forEach { id ->
            assertFalse(
                "$id never asks the user anything, so OVERLAY would be a false badge",
                NodeTypeRegistry.byId(id)!!.permissionRequirements
                    .any { it.type == PrerequisiteType.OVERLAY },
            )
        }
    }

    /** The microphone is a runtime grant, so it has a manifest name to request. */
    @Test
    fun `the microphone grant carries a manifest name`() {
        val requirement = NodeTypeRegistry.byId(start)!!.permissionRequirements.single()
        assertNotNull("a RUNTIME prerequisite is requested by name", requirement.manifestPermission)
        assertNull(
            "there is no API-level rename to work around here",
            requirement.manifestPermission?.takeIf { it != Permissions.RECORD_AUDIO.manifest },
        )
    }
}
