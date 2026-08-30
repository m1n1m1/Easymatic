package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.LogEntry
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.core.service.Translation
import io.github.m1n1m1.easymatic.core.service.TranslationOutcome
import io.github.m1n1m1.easymatic.core.service.TranslationRequest
import io.github.m1n1m1.easymatic.engine.DefaultExecutionContext
import io.github.m1n1m1.easymatic.engine.ExecutionRoute
import io.github.m1n1m1.easymatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A translator that answers whatever it is told to and records what it was asked.
 *
 * In this file rather than in `data/` because there is nothing of ML Kit's to fake: the whole
 * point of the `Translation` interface is that the node's behaviour — what a detect-mode config
 * sends, where a refusal lands — is decided against strings and needs no device.
 */
private class FakeTranslation : Translation {
    var outcome = TranslationOutcome(text = "translated")
    val requests = mutableListOf<TranslationRequest>()

    override suspend fun translate(request: TranslationRequest): TranslationOutcome {
        requests += request
        return outcome
    }
}

/**
 * `action.translate`'s behaviour, and mostly its refusals.
 *
 * The node's whole job on the happy path is one facade call, so what is worth pinning is the
 * mapping it does on the way in and the branching it does on the way out — specifically that
 * `Detect automatically` becomes a *blank* source rather than the stale contents of a hidden
 * field, and that every refusal reaches the fallback while still pulsing `out`.
 */
class TranslateActionTest {

    private val logs = mutableListOf<LogEntry>()
    private val translator = FakeTranslation()
    private val context = DefaultExecutionContext(
        systemServices = RecordingSystemServices(),
        translation = translator,
        logger = { logs += it },
    )
    private val action = TranslateAction()

    @Test
    fun `detect mode sends a blank source so the facade works it out`() = runBlocking {
        translator.outcome = TranslationOutcome(text = "Good morning", sourceLanguage = "de")

        val out = action.execute(
            TranslateConfig(text = "Guten Morgen", targetLanguage = "en"),
            context,
        )

        assertEquals("Good morning", out.value)
        assertEquals("", translator.requests.single().sourceLanguage)
        assertEquals("en", translator.requests.single().targetLanguage)
    }

    @Test
    fun `detect mode ignores a stale source language left in the hidden field`() = runBlocking {
        // The field is hidden by `@VisibleWhen` but still decodes, so a user who chose a
        // language and then switched back to detection leaves a value behind. Sending it would
        // silently un-do the switch — the mode enum is the only thing that may decide this.
        action.execute(
            TranslateConfig(
                text = "Guten Morgen",
                sourceMode = TranslateSource.DETECT,
                sourceLanguage = "fr",
                targetLanguage = "en",
            ),
            context,
        )

        assertEquals("", translator.requests.single().sourceLanguage)
    }

    @Test
    fun `chosen mode sends the named language`() = runBlocking {
        action.execute(
            TranslateConfig(
                text = "Guten Morgen",
                sourceMode = TranslateSource.CHOSEN,
                sourceLanguage = "de",
                targetLanguage = "en",
            ),
            context,
        )

        assertEquals("de", translator.requests.single().sourceLanguage)
    }

    @Test
    fun `a refusal lands on the fallback and still pulses out`() = runBlocking {
        // The realistic one: a language whose model was deleted from the settings screen after
        // the macro was built. The node cannot fetch it, so it reports and falls back.
        translator.outcome = TranslationOutcome(error = "Not downloaded: de.")

        val out = action.execute(
            TranslateConfig(text = "Guten Morgen", targetLanguage = "en", fallback = "…"),
            context,
        )

        assertEquals("…", out.value)
        assertEquals(ExecutionRoute.OUT, out.route)
        assertEquals(false, out.halt)
        assertTrue(logs.any { it.level == LogLevel.ERROR && it.message.contains("Not downloaded") })
    }

    @Test
    fun `blank text never reaches the translator`() = runBlocking {
        val out = action.execute(
            TranslateConfig(text = "  ", targetLanguage = "en", fallback = "…"),
            context,
        )

        assertEquals("…", out.value)
        assertEquals(ExecutionRoute.OUT, out.route)
        assertTrue("nothing should have been sent", translator.requests.isEmpty())
    }

    @Test
    fun `a blank target is refused rather than defaulting to the phone's language`() = runBlocking {
        val out = action.execute(TranslateConfig(text = "hello", fallback = "…"), context)

        assertEquals("…", out.value)
        assertTrue("nothing should have been sent", translator.requests.isEmpty())
        assertTrue(logs.any { it.level == LogLevel.ERROR })
    }

    @Test
    fun `the language it decided on is reported for a detected translation`() = runBlocking {
        // On DETECT this is the only place the answer exists at all, and it is the first thing
        // anybody wants when a translation comes back wrong.
        translator.outcome = TranslationOutcome(text = "Good morning", sourceLanguage = "de")

        action.execute(TranslateConfig(text = "Guten Morgen", targetLanguage = "en"), context)

        assertTrue(logs.any { it.message.contains("'de'") && it.message.contains("'en'") })
    }
}
