package com.example.ottomatic.feature.i18n

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.NodeTypeDefinition
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.domain.registry.NodeTypeRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What [NodeText] does when a key is missing — the case a real `Resources` is worst
 * at producing on demand, which is why it takes a lookup instead of one.
 */
class NodeTextFallbackTest {

    private val german = NodeText { key -> GERMAN[key] }
    private val untranslated = NodeText { null }

    @Test
    fun `a translated key wins over the declaration's literal`() {
        assertEquals("SMS senden", german.name(sendSms))
        assertEquals("Sendet eine SMS", german.description(sendSms))
    }

    @Test
    fun `with nothing translated every node reads exactly as declared`() {
        NodeTypeRegistry.all.forEach {
            assertEquals(it.displayName, untranslated.name(it))
            assertEquals(it.description, untranslated.description(it))
        }
    }

    /**
     * The plugin case is why the fallback exists rather than a degradation it happens
     * to cover: the text crossed the binder already rendered by its author, no key was
     * ever generated for it, and none ever will be.
     */
    @Test
    fun `a plugin node keeps the text it arrived with`() {
        val plugin = NodeTypeDefinition(
            typeId = NodeTypeId("acme.shout"),
            displayName = "Shout",
            description = "Shouts a message",
            kind = NodeKind.ACTION,
            category = NodeCategory.PLUGIN_ACTION,
            ports = emptyList(),
            icon = NodeIcon.BOLT,
        )
        assertEquals("Shout", german.name(plugin))
        assertEquals("Shouts a message", german.description(plugin))
    }

    @Test
    fun `a dot in the typeId becomes an underscore in the key`() {
        assertEquals("action_send_sms", NodeTypeId("action.send_sms").slug())
    }

    @Test
    fun `a field's hint is translated on a key of its own`() {
        assertEquals("Sekunden, 0 = die ganze Zeit", german.fieldHint(sendSms.typeId, silence))
        assertEquals("Wie lange", german.fieldLabel(sendSms.typeId, silence))
    }

    /**
     * A field that says everything in its name has no hint key, so the lookup misses and the
     * declaration's blank comes back — which is what makes the ⓘ absent rather than a button
     * that opens onto nothing.
     */
    @Test
    fun `a field with nothing to explain answers blank in every locale`() {
        val plain = ConfigField(ConfigKey("title"), "Title", ConfigFieldType.STR)

        assertEquals("", german.fieldHint(sendSms.typeId, plain))
        assertEquals("", untranslated.fieldHint(sendSms.typeId, plain))
    }

    /** The plugin case again: its author's words crossed the binder, and no key names them. */
    @Test
    fun `an untranslated hint falls back to the declaration`() {
        assertEquals("seconds, 0 = the whole time", untranslated.fieldHint(sendSms.typeId, silence))
    }

    @Test
    fun `snake splits camel case and lowercases the rest`() {
        assertEquals("days_of_week", "daysOfWeek".snake())
        assertEquals("play_pause", "PLAY_PAUSE".snake())
        assertEquals("time_schedule", "TIME_SCHEDULE".snake())
    }

    private companion object {
        val sendSms = NodeTypeDefinition(
            typeId = NodeTypeId("action.send_sms"),
            displayName = "Send SMS",
            description = "Sends an SMS to a number with a body",
            kind = NodeKind.ACTION,
            category = NodeCategory.MESSAGING,
            ports = emptyList(),
            icon = NodeIcon.BOLT,
        )

        val silence = ConfigField(
            key = ConfigKey("silenceSeconds"),
            label = "How long",
            type = ConfigFieldType.STR,
            hint = "seconds, 0 = the whole time",
        )

        val GERMAN = mapOf(
            "node_action_send_sms_name" to "SMS senden",
            "node_action_send_sms_desc" to "Sendet eine SMS",
            "cfg_action_send_sms_silence_seconds" to "Wie lange",
            "hint_action_send_sms_silence_seconds" to "Sekunden, 0 = die ganze Zeit",
        )
    }
}
