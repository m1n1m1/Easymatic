package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.config.PickerKind
import com.example.ottomatic.domain.model.schema.ItemSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mail nodes as the registry sees them, on `NfcTagRegistryTest`'s template.
 */
class MailRegistryTest {

    private val send = NodeTypeId("action.send_mail")
    private val fetch = NodeTypeId("action.fetch_mail")
    private val update = NodeTypeId("action.mail_update")
    private val trigger = NodeTypeId("trigger.mail")

    private fun type(id: NodeTypeId) = NodeTypeRegistry.byId(id)

    private fun fields(id: NodeTypeId) = ConfigSchemaRegistry.byId(id)!!.fields

    @Test
    fun `all four are registered under the ids workflows store`() {
        assertNotNull(ActionRegistry.byId(send))
        assertNotNull(ActionRegistry.byId(fetch))
        assertNotNull(ActionRegistry.byId(update))
        assertNotNull(TriggerRegistry.byId(trigger))
        assertEquals(NodeKind.ACTION, type(send)?.kind)
        assertEquals(NodeKind.TRIGGER, type(trigger)?.kind)
    }

    @Test
    fun `the trigger is one exec out and one data out, with no data in`() {
        val ports = type(trigger)!!.ports
        assertEquals(1, ports.count { it.kind == PortKind.EXECUTION })
        assertTrue(ports.none { it.kind == PortKind.DATA && it.direction == Direction.IN })
        val data = ports.single { it.kind == PortKind.DATA && it.direction == Direction.OUT }
        assertEquals(PortName("mail"), data.name)
    }

    /**
     * A list port, which is what `action.for_each` accepts — the shape this node
     * exists for is fetch → for-each → act on each message.
     */
    @Test
    fun `fetch answers with a list`() {
        val port = type(fetch)!!.ports.single { it.name == PortName("messages") }
        assertTrue(port.schema.toString(), port.schema is ItemSchema.ListSchema)
    }

    @Test
    fun `every account field is a picker`() {
        listOf(send, fetch, trigger).forEach { id ->
            val field = fields(id).single { it.key == ConfigKey("accountId") }
            assertEquals(id.value, ConfigFieldType.PICKER(PickerKind.MAIL_ACCOUNT), field.type)
        }
    }

    /** The destination only exists for a move, which is what `@VisibleWhen` is for. */
    @Test
    fun `the destination folder is hidden unless the operation is a move`() {
        val field = fields(update).single { it.key == ConfigKey("targetFolder") }
        val rule = field.visibleWhen
        assertNotNull(rule)
        assertEquals(ConfigKey("op"), rule?.key)
        assertEquals(setOf("MOVE"), rule?.values)
    }

    /**
     * The deliberate absence, pinned so it cannot be "fixed" later. INTERNET is
     * install-time, is already in the manifest, and gets no `Permissions` constant
     * — the rule the manifest states for `android.permission.NFC`. What actually
     * stops a mail node is a password, which is not a permission and is reported
     * on the account.
     */
    @Test
    fun `no mail node declares a permission`() {
        listOf(send, fetch, update, trigger).forEach { id ->
            assertTrue(id.value, type(id)!!.permissionRequirements.isEmpty())
        }
    }

    @Test
    fun `the trigger defaults to the inbox and to unread only`() {
        val schema = ConfigSchemaRegistry.byId(trigger)!!
        assertEquals("INBOX", schema.fields.single { it.key == ConfigKey("folder") }.defaultValue)
        assertEquals("true", schema.fields.single { it.key == ConfigKey("unreadOnly") }.defaultValue)
    }
}
