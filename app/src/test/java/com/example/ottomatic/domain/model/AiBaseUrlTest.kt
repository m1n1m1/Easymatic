package com.example.ottomatic.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a typed AI server address means.
 *
 * The first test is the one this file exists for: [WebUrl] would https-ify a bare LAN
 * address and produce a TLS failure that reads like the server being down, sending
 * somebody to look at vLLM's logs where nothing is wrong.
 */
class AiBaseUrlTest {

    @Test
    fun `a scheme-less address is refused rather than silently made https`() {
        assertNull(AiBaseUrl.parse("192.168.1.10:8000/v1"))
        assertNull(AiBaseUrl.parse("api.openai.com/v1"))
        assertNull(AiBaseUrl.parse("localhost:11434"))
    }

    @Test
    fun `the refusal comes with a sentence naming both schemes`() {
        assertTrue(AiBaseUrl.REQUIREMENT.contains("http://"))
        assertTrue(AiBaseUrl.REQUIREMENT.contains("https://"))
    }

    @Test
    fun `an address with a scheme is kept as it was typed`() {
        assertEquals("http://192.168.1.10:8000/v1", AiBaseUrl.parse("http://192.168.1.10:8000/v1"))
        assertEquals("https://api.openai.com/v1", AiBaseUrl.parse("https://api.openai.com/v1"))
    }

    @Test
    fun `surrounding whitespace from a paste is trimmed`() {
        assertEquals("https://api.openai.com/v1", AiBaseUrl.parse("  https://api.openai.com/v1\n"))
    }

    /** Every caller appends a path that starts with one, and `//models` is not `/models`. */
    @Test
    fun `a trailing slash is dropped`() {
        assertEquals("https://api.openai.com/v1", AiBaseUrl.parse("https://api.openai.com/v1/"))
    }

    /**
     * The likeliest mistake this field will ever see: pasting the endpoint straight
     * out of a provider's docs. Left alone it produces
     * `…/chat/completions/chat/completions` and a 404 that names nothing.
     */
    @Test
    fun `a pasted chat completions endpoint is cut back to its base`() {
        assertEquals(
            "https://api.openai.com/v1",
            AiBaseUrl.parse("https://api.openai.com/v1/chat/completions"),
        )
        assertEquals(
            "http://192.168.1.10:8000/v1",
            AiBaseUrl.parse("http://192.168.1.10:8000/v1/chat/completions/"),
        )
    }

    @Test
    fun `a scheme with nothing after it is not an address`() {
        assertNull(AiBaseUrl.parse("https://"))
        assertNull(AiBaseUrl.parse("http:// "))
    }

    @Test
    fun `blank text is not an address`() {
        assertNull(AiBaseUrl.parse(""))
        assertNull(AiBaseUrl.parse("   "))
    }

    @Test
    fun `a sentence that is not a URL is refused rather than guessed at`() {
        assertNull(AiBaseUrl.parse("my ollama server"))
    }

    /**
     * A warning and not a refusal: `http://` to a box in your own house is how these
     * servers ship, and only the user knows whether the far end is their living room
     * or somebody else's server.
     */
    @Test
    fun `an unencrypted address is recognised so the editor can warn about it`() {
        assertTrue(AiBaseUrl.isCleartext("http://192.168.1.10:8000/v1"))
        assertTrue(AiBaseUrl.isCleartext("  HTTP://192.168.1.10:8000  "))
        assertFalse(AiBaseUrl.isCleartext("https://api.openai.com/v1"))
        assertFalse(AiBaseUrl.isCleartext(""))
    }
}
