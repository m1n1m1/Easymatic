package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.config.PickerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `action.translate`'s wiring, and mostly the judgement calls that nothing else would catch.
 *
 * Four of the five things pinned here are decisions somebody could quietly reverse while every
 * other test in the suite stayed green: the category, the icon, the absent permission and the
 * absent capability. The fifth — that the language fields are pickers rather than suggestions —
 * is the one with a *functional* consequence rather than a cosmetic one, and it is the reason
 * this file exists at all.
 *
 * **A `@Suggested` language field would still render, still store a tag, and still work by
 * hand.** What it would break is invisible from the editor: `NodeToolCatalog.parameterFor` turns
 * a `PICKER` into an enumeration the AI provider itself enforces and everything else into free
 * text, so a model asked to translate something would invent `de-AT`, the node would find no
 * such language, and the run would look like a translation that silently did nothing. Changing
 * one annotation is all it would take, and only this assertion would notice.
 */
class TranslateRegistryTest {

    private val translate = NodeTypeId("action.translate")

    private val definition = requireNotNull(ActionRegistry.byId(translate)).definition

    @Test
    fun `it is registered as an action`() {
        assertNotNull("must reach NodeTypeRegistry", NodeTypeRegistry.byId(translate))
        assertEquals(NodeKind.ACTION, definition.nodeType.kind)
    }

    @Test
    fun `it sits with the AI nodes rather than with the data nodes`() {
        // What the AI section has in common is a model turning one kind of language into
        // another, not an account being billed for it — `action.transcribe`'s phone mode is
        // the precedent. `DATA` holds graph plumbing, which this is not.
        assertEquals(NodeCategory.AI, definition.nodeType.category)
    }

    @Test
    fun `it wears its own icon rather than the AI brain`() {
        // Borrowing `AI` would say the one thing about this node that is untrue: it needs no
        // account, no key and no network. See `NodeIcon.TRANSLATE`.
        assertEquals(NodeIcon.TRANSLATE, definition.nodeType.icon)
    }

    @Test
    fun `it declares no permission and no capability`() {
        // INTERNET is granted at install and never checked, so a requirement for it would draw
        // a Permissions row that is green forever. And there is no hardware here that can be
        // absent — the bundled ML Kit artifact runs on every phone — so a capability would put
        // a permanent amber badge in the Problems panel on a device that runs this perfectly.
        // That is `value.nfc`'s failure and the mistake most likely to be made here.
        assertEquals(emptyList<Any>(), definition.nodeType.permissionRequirements)
        assertEquals(emptyList<Any>(), definition.nodeType.capabilities)
    }

    @Test
    fun `both language fields are read-only pickers so a model cannot invent a tag`() {
        val fields = definition.schema.fields.associateBy { it.key.value }
        listOf("sourceLanguage", "targetLanguage").forEach { key ->
            val type = requireNotNull(fields[key]) { "$key must be declared" }.type
            assertTrue(
                "$key must be a picker, not free text — see the class KDoc",
                type is ConfigFieldType.PICKER,
            )
            assertEquals(PickerKind.TRANSLATE_LANGUAGE, (type as ConfigFieldType.PICKER).kind)
        }
    }

    @Test
    fun `the tool harness is offered the downloaded languages, not the supported ones`() {
        // The whole payoff of the picker: `PickerOptions` can enumerate this kind, which hands a
        // model a closed set instead of a text box. That set must be what is **downloaded** —
        // translation cannot happen without the model, so offering the library's full list would
        // be offering a model configurations that are refused on their first run.
        TranslateLanguages.hydrateSupported(listOf("de", "en", "fr", "ja", "pt"))
        TranslateLanguages.hydrateDownloaded(listOf("de", "en"))
        try {
            assertEquals(listOf("de", "en"), PickerOptions.of(PickerKind.TRANSLATE_LANGUAGE))
        } finally {
            TranslateLanguages.reset()
        }
    }

    @Test
    fun `an unpublished language list narrows nothing rather than offering nothing`() {
        // `PickerOptions`' degradation rule: empty means "cannot be enumerated", so the field
        // stays required-to-pin for a tool instead of offering an empty choice.
        TranslateLanguages.reset()
        assertEquals(emptyList<String>(), PickerOptions.of(PickerKind.TRANSLATE_LANGUAGE))
    }

    @Test
    fun `the two language lists do not erase one another`() {
        // Separate setters for `SpeechLanguages`' reason: they arrive at different moments — the
        // supported list once at construction, the downloaded list again after every add and
        // remove — and one setter would let whichever came second erase the other.
        TranslateLanguages.hydrateSupported(listOf("de", "en", "fr"))
        TranslateLanguages.hydrateDownloaded(listOf("de"))
        TranslateLanguages.hydrateDownloaded(listOf("de", "fr"))
        try {
            assertEquals(listOf("de", "en", "fr"), TranslateLanguages.supported())
            assertEquals(listOf("de", "fr"), TranslateLanguages.downloaded())
        } finally {
            TranslateLanguages.reset()
        }
    }

    @Test
    fun `it produces one text output named translation`() {
        val output = requireNotNull(definition.output)
        assertEquals("translation", output.name.value)
    }
}
