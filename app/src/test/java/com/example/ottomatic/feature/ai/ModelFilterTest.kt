package com.example.ottomatic.feature.ai

import com.example.ottomatic.data.ai.AiModelInfo
import com.example.ottomatic.domain.model.AiModality
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one rule the model chooser's filter has to get right.
 *
 * **A model whose modalities are unknown is never hidden.** Only OpenRouter publishes
 * `architecture.input_modalities`; Gemini publishes generation methods and no modality
 * field, and OpenAI, Anthropic and a self-hosted server publish little beyond ids. So the
 * field is null on four providers out of five, and reading null as "does not accept" —
 * which `modalities.orEmpty().containsAll(...)` would do, and is the obvious
 * simplification somebody will reach for — empties the chooser on all four the moment a
 * chip is ticked.
 *
 * `CapabilityStatus.UNKNOWN`'s stance and `PickerOptions`' degradation rule, in the one
 * place a user can see the difference.
 */
class ModelFilterTest {

    private val openRouterAudio = AiModelInfo(
        id = "google/gemini-3.6-flash",
        modalities = setOf(AiModality.TEXT, AiModality.IMAGE, AiModality.AUDIO),
    )
    private val openRouterTextOnly = AiModelInfo(
        id = "meta/llama-4",
        modalities = setOf(AiModality.TEXT),
    )
    private val unpublished = AiModelInfo(id = "gpt-5.1")

    @Test
    fun `an empty filter keeps everything`() {
        listOf(openRouterAudio, openRouterTextOnly, unpublished).forEach {
            assertTrue(it.id, it.matches(emptySet()))
        }
    }

    @Test
    fun `a published model is kept only when it accepts what was asked for`() {
        assertTrue(openRouterAudio.matches(setOf(AiModality.AUDIO)))
        assertFalse(openRouterTextOnly.matches(setOf(AiModality.AUDIO)))
    }

    /** The line this file exists for. */
    @Test
    fun `a model that published nothing survives every filter`() {
        assertTrue(unpublished.matches(setOf(AiModality.AUDIO)))
        assertTrue(unpublished.matches(setOf(AiModality.IMAGE)))
        assertTrue(unpublished.matches(setOf(AiModality.AUDIO, AiModality.IMAGE)))
    }

    @Test
    fun `two chips narrow to models accepting both`() {
        assertTrue(openRouterAudio.matches(setOf(AiModality.AUDIO, AiModality.IMAGE)))
        assertFalse(openRouterTextOnly.matches(setOf(AiModality.AUDIO, AiModality.IMAGE)))
    }
}
