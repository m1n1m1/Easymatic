package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.PickerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every field that is chosen rather than typed, pinned one assertion at a time.
 *
 * This is the test that fails when somebody drops an annotation while editing a
 * config class: nothing else would notice, because a missing `@Picker` degrades
 * silently into a plain text field that still stores the same string.
 */
class PickerFieldsTest {

    private fun fieldType(typeId: String, key: String) =
        ConfigSchemaRegistry.byId(NodeTypeId(typeId))!!.fields.first { it.key == ConfigKey(key) }.type

    @Test
    fun `phone numbers get the contact-assisted field`() {
        assertEquals(ConfigFieldType.PHONE, fieldType("action.call", "number"))
        assertEquals(ConfigFieldType.PHONE, fieldType("action.send_sms", "to"))
        assertEquals(ConfigFieldType.PHONE, fieldType("action.send_message", "to"))
        assertEquals(ConfigFieldType.PHONE, fieldType("trigger.sms", "sender"))
    }

    /**
     * The message trigger's sender is a **name**, not a number, and the two must not
     * be allowed to converge. An SMS arrives with a number, so `trigger.sms` can hold
     * a `PhoneRef` and resolve it; a messenger notification carries no number at all,
     * so a `PhoneRef` here would store something nothing could ever compare against —
     * and would additionally make the node ask for contacts access it never uses.
     */
    @Test
    fun `a message sender is a contact name rather than a phone reference`() {
        assertEquals(ConfigFieldType.CONTACT_NAME, fieldType("trigger.message", "senderContains"))
    }

    /** It follows that this node is not one that needs the address book at run time. */
    @Test
    fun `the message trigger needs no contacts access`() {
        assertTrue(phoneRefKeys(NodeTypeId("trigger.message")).isEmpty())
    }

    /**
     * Two app kinds, not one: `action.launch_app` wants apps it can actually open,
     * while a filter wants any package and accepts "any app" as an answer.
     */
    @Test
    fun `apps are chosen from the installed ones`() {
        assertEquals(
            ConfigFieldType.PICKER(PickerKind.APP),
            fieldType("action.launch_app", "packageName"),
        )
        assertEquals(
            ConfigFieldType.PICKER(PickerKind.APP_FILTER),
            fieldType("trigger.notification", "packageFilter"),
        )
        assertEquals(
            ConfigFieldType.PICKER(PickerKind.APP_FILTER),
            fieldType("trigger.app_installed", "packageFilter"),
        )
    }

    @Test
    fun `macros are chosen rather than named by UUID`() {
        assertEquals(
            ConfigFieldType.PICKER(PickerKind.MACRO),
            fieldType("action.enable_macro", "macroId"),
        )
        assertEquals(
            ConfigFieldType.PICKER(PickerKind.MACRO),
            fieldType("action.disable_macro", "macroId"),
        )
    }

    /**
     * A tag's hardware id is the most opaque identifier in the app — nobody knows
     * one, and a mistyped one names a tag that does not exist while looking exactly
     * like a correct one. So this must never become an editable field the way a
     * network name legitimately is.
     */
    @Test
    fun `an NFC tag is captured rather than typed`() {
        assertEquals(
            ConfigFieldType.PICKER(PickerKind.NFC_TAG),
            fieldType("trigger.nfc", "tagId"),
        )
    }

    /**
     * A mail account id is a UUID, so it belongs with the macro id and the tag id
     * on the opaque side of the line: a typed one names nothing and looks exactly
     * like a correct one. What it stands for is not typeable either — a host, a
     * port, a username and a sealed password.
     */
    @Test
    fun `a mail account is chosen rather than typed`() {
        assertEquals(
            ConfigFieldType.PICKER(PickerKind.MAIL_ACCOUNT),
            fieldType("action.send_mail", "accountId"),
        )
    }

    /**
     * The fourth editable-with-a-chooser field, and the one whose editability is
     * least obvious: a folder name looks typeable, which is why it was a plain text
     * field first. Gmail's are bracketed and localised — `[Gmail]/Alle Nachrichten`
     * — so the chooser is what makes it usable, and the field stays editable
     * because listing folders needs a network and a working password.
     */
    @Test
    fun `a mailbox is typed with a folder list beside it`() {
        assertEquals(
            ConfigFieldType.MAIL_FOLDER(accountKey = "accountId"),
            fieldType("trigger.mail", "folder"),
        )
        assertEquals(
            ConfigFieldType.MAIL_FOLDER(accountKey = "accountId"),
            fieldType("action.fetch_mail", "folder"),
        )
        // No sibling holds this node's account — it arrives inside the wired
        // message reference — so the chooser asks which account first.
        assertEquals(
            ConfigFieldType.MAIL_FOLDER(accountKey = ""),
            fieldType("action.mail_update", "targetFolder"),
        )
    }

    /**
     * A bridge names its lights with UUIDs, so this is on the opaque side of the
     * line with the macro, tag and account ids. What is stored is a whole
     * `SmartHomeRef` spec rather than a bare id, and that is what keeps the hub out
     * of a second field beside it: two fields could name a hub and a light that do
     * not belong together, with nothing to detect it.
     */
    @Test
    fun `a light and a scene are chosen rather than typed`() {
        assertEquals(
            ConfigFieldType.PICKER(PickerKind.LIGHT_TARGET),
            fieldType("action.light_control", "target"),
        )
        assertEquals(
            ConfigFieldType.PICKER(PickerKind.LIGHT_TARGET),
            fieldType("action.light_state", "target"),
        )
        assertEquals(
            ConfigFieldType.PICKER(PickerKind.LIGHT_SCENE),
            fieldType("action.light_scene", "scene"),
        )
    }

    @Test
    fun `schedule times get a clock face rather than a calendar`() {
        assertEquals(ConfigFieldType.TIME_OF_DAY, fieldType("trigger.schedule", "atTime"))
        assertEquals(ConfigFieldType.TIME_OF_DAY, fieldType("trigger.schedule", "windowFrom"))
        assertEquals(ConfigFieldType.TIME_OF_DAY, fieldType("trigger.schedule", "windowUntil"))
    }

    /**
     * The third editable-with-a-chooser field, and the one whose editability is least
     * negotiable: the network somebody is automating for is usually not the one they
     * are standing next to, so this must never quietly become a `PICKER`.
     */
    @Test
    fun `a network is typed with a scan beside it rather than picked from one`() {
        assertEquals(ConfigFieldType.WIFI_NETWORK, fieldType("trigger.wifi_network", "ssid"))
    }

    @Test
    fun `macro ref keys are derived from the schema`() {
        assertEquals(
            listOf(ConfigKey("macroId")),
            macroRefKeys(NodeTypeId("action.enable_macro")),
        )
        assertTrue(macroRefKeys(NodeTypeId("action.notify")).isEmpty())
    }

    @Test
    fun `phone ref keys are derived from the schema`() {
        assertEquals(listOf(ConfigKey("to")), phoneRefKeys(NodeTypeId("action.send_sms")))
        assertTrue(phoneRefKeys(NodeTypeId("action.notify")).isEmpty())
    }

    /**
     * Contacts access is a property of what the user put in the field, not of the
     * node type — which is the whole reason it is derived rather than declared.
     */
    @Test
    fun `only a node holding a contact reference needs contacts access`() {
        assertTrue(usesContacts(callNodeWith("contact:0r3-2A%7C44|Mum")))
        assertFalse(usesContacts(callNodeWith("+436761234567")))
        assertFalse(usesContacts(callNodeWith("")))
    }

    private fun callNodeWith(number: String) = WorkflowNode(
        id = NodeId("n1"),
        typeId = NodeTypeId("action.call"),
        name = "Call",
        x = 0f,
        y = 0f,
        config = mapOf(ConfigKey("number") to number),
    )
}
