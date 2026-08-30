package io.github.m1n1m1.easymatic.domain.registry

import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.core.model.PortName
import io.github.m1n1m1.easymatic.core.permissions.PrerequisiteType
import io.github.m1n1m1.easymatic.domain.model.Direction
import io.github.m1n1m1.easymatic.domain.model.NodeKind
import io.github.m1n1m1.easymatic.domain.model.PortKind
import io.github.m1n1m1.easymatic.domain.model.config.PickerKind
import io.github.m1n1m1.easymatic.domain.model.schema.ItemSchema
import io.github.m1n1m1.easymatic.engine.trigger.NfcTagTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies `trigger.nfc` and `value.nfc` are wired into every central registry, and
 * pins the three declarations that would otherwise degrade silently: the tag field's
 * chooser, the prerequisite that puts the node in the Problems panel, and the value
 * node's deliberate lack of one.
 */
class NfcTagRegistryTest {

    @Test
    fun `the trigger is registered with the correct typeId`() {
        val trigger = TriggerRegistry.byId(NfcTagTrigger.TYPE_ID)
        assertNotNull("trigger.nfc must be registered in TriggerRegistry", trigger)
        assertEquals(NfcTagTrigger.TYPE_ID, trigger!!.typeId)
    }

    @Test
    fun `node type definition is a trigger exposing one exec out and one typed data out`() {
        val def = NodeTypeRegistry.byId(NfcTagTrigger.TYPE_ID)
        assertNotNull("trigger.nfc must be registered in NodeTypeRegistry", def)
        assertEquals(NodeKind.TRIGGER, def!!.kind)
        val exec = def.ports.filter { it.kind == PortKind.EXECUTION }
        val dataOut = def.ports.filter { it.kind == PortKind.DATA && it.direction == Direction.OUT }
        val dataIn = def.ports.filter { it.kind == PortKind.DATA && it.direction == Direction.IN }
        assertEquals("exactly one EXECUTION port", 1, exec.size)
        assertEquals(PortName("out"), exec.first().name)
        assertEquals("exactly one DATA output port named 'tag'", 1, dataOut.size)
        assertEquals(PortName("tag"), dataOut.first().name)
        assertTrue("triggers must not expose data inputs", dataIn.isEmpty())
    }

    /**
     * Blank means "any tag", which is what makes an untouched node run on every tap
     * rather than on nothing at all.
     */
    @Test
    fun `the tag filter is unset by default`() {
        val schema = ConfigSchemaRegistry.byId(NfcTagTrigger.TYPE_ID)
        assertNotNull("trigger.nfc must have a config schema", schema)
        assertEquals("", schema!!.fields.first { it.key == ConfigKey("tagId") }.defaultValue)
    }

    /**
     * The assertion that fails when somebody drops the annotation while editing the
     * config class: without it the field degrades into a plain text box storing the
     * same string, and a uid is the one thing nobody can type.
     */
    @Test
    fun `the tag is chosen rather than typed`() {
        val schema = ConfigSchemaRegistry.byId(NfcTagTrigger.TYPE_ID)!!
        val field = schema.fields.first { it.key == ConfigKey("tagId") }
        assertEquals(ConfigFieldType.PICKER(PickerKind.NFC_TAG), field.type)
    }

    @Test
    fun `tag port schema is derived from NfcScan`() {
        val def = NodeTypeRegistry.byId(NfcTagTrigger.TYPE_ID)!!
        val port = def.ports.first { it.name == PortName("tag") }
        val schema = port.schema as ItemSchema.Object
        assertEquals(setOf("tagId", "tagName", "text", "timestamp"), schema.fields.keys)
    }

    /**
     * Not a manifest permission: `android.permission.NFC` is install-time and always
     * held, and what actually stops this trigger firing is a system-wide toggle.
     * Declaring it is what puts the node in the Problems panel and on the
     * Permissions screen.
     */
    @Test
    fun `the trigger declares the NFC radio as a prerequisite`() {
        val requirement = NodeTypeRegistry.byId(NfcTagTrigger.TYPE_ID)!!.permissionRequirements.single()
        assertEquals(PrerequisiteType.NFC, requirement.type)
        assertEquals(null, requirement.manifestPermission)
    }

    /**
     * The mirror of the test above, and the more interesting half: a value node that
     * answers *"is the radio on?"* must never be badged for the radio being off.
     * That is the node working.
     */
    @Test
    fun `the value deliberately declares nothing`() {
        val def = NodeTypeRegistry.byId(VALUE_TYPE_ID)
        assertNotNull("value.nfc must be registered in NodeTypeRegistry", def)
        assertEquals(NodeKind.VALUE, def!!.kind)
        assertTrue(
            "a value answering whether NFC is on must not require NFC to be on",
            def.permissionRequirements.isEmpty(),
        )
        assertTrue("a value declares no exec ports", def.ports.none { it.kind == PortKind.EXECUTION })
        val output = def.ports.single()
        assertEquals(PortName("enabled"), output.name)
        assertEquals(ItemSchema.Primitive(Boolean::class), output.schema)
    }

    private companion object {
        val VALUE_TYPE_ID = NodeTypeId("value.nfc")
    }
}
