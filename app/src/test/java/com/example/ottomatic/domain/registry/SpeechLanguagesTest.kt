package com.example.ottomatic.domain.registry

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which languages the listening dropdown offers, and why it is not simply "all of them".
 *
 * **Supported and installed differ by two orders of magnitude, and only one of them works.**
 * A modern phone's recogniser reports well over a hundred supported languages and has three
 * or four models actually downloaded. Offering the long list is offering mostly wrong
 * answers: each one renders perfectly in the form, is accepted, and then fails at run time
 * with a message about a language pack. So installed wins wherever the phone will say.
 *
 * The fallback is the honest half. Installed is only knowable from API 33, and a recogniser
 * may decline to answer at all — a longer list beats the empty one this field showed until
 * 2026-08-29, which drew no chooser button and left a BCP-47 tag to be typed from memory.
 */
class SpeechLanguagesTest {

    @After
    fun tearDown() = SpeechLanguages.reset()

    @Test
    fun `installed languages win over merely supported ones`() {
        SpeechLanguages.hydrateListening(listOf("de-DE", "en-GB", "fr-FR", "sw-KE"))
        SpeechLanguages.hydrateInstalledListening(listOf("de-DE", "en-GB"))

        assertEquals(listOf("de-DE", "en-GB"), SpeechLanguages.forListening())
        assertTrue(SpeechLanguages.listeningIsInstalledOnly())
    }

    /** API 32 and below, and any recogniser that will not answer. */
    @Test
    fun `with nothing installed reported it falls back to supported`() {
        SpeechLanguages.hydrateListening(listOf("de-DE", "en-GB"))

        assertEquals(listOf("de-DE", "en-GB"), SpeechLanguages.forListening())
        assertFalse(SpeechLanguages.listeningIsInstalledOnly())
    }

    /**
     * An *empty* installed list is not an answer either. A recogniser that reports nothing
     * downloaded would otherwise empty the dropdown completely, which is the exact state
     * this change exists to fix.
     */
    @Test
    fun `an empty installed list falls back rather than emptying the dropdown`() {
        SpeechLanguages.hydrateListening(listOf("de-DE", "en-GB"))
        SpeechLanguages.hydrateInstalledListening(emptyList())

        assertEquals(listOf("de-DE", "en-GB"), SpeechLanguages.forListening())
        assertFalse(SpeechLanguages.listeningIsInstalledOnly())
    }

    /** `Suggestions`' governing rule: empty narrows nothing, it never means "there is nothing". */
    @Test
    fun `unhydrated answers empty so the field stays a plain text box`() {
        assertEquals(emptyList<String>(), SpeechLanguages.forListening())
        assertFalse(SpeechLanguages.isHydrated)
    }

    /** The two halves are published by unrelated software and must not erase each other. */
    @Test
    fun `speaking and listening lists are independent`() {
        SpeechLanguages.hydrateSpeaking(listOf("en-US", "es-ES", "it-IT"))
        SpeechLanguages.hydrateInstalledListening(listOf("en-US"))

        assertEquals(listOf("en-US", "es-ES", "it-IT"), SpeechLanguages.forSpeaking())
        assertEquals(listOf("en-US"), SpeechLanguages.forListening())
    }
}
