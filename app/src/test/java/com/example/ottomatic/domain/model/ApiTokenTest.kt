package com.example.ottomatic.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiTokenTest {

    @Test
    fun `a generated token is long enough to be worth generating`() {
        // 24 bytes, Base64url without padding — 32 characters.
        assertEquals(32, ApiTokens.generate().length)
    }

    @Test
    fun `generated tokens differ`() {
        val tokens = List(64) { ApiTokens.generate() }
        assertEquals("a repeated token would make every trigger on the device the same one", 64, tokens.toSet().size)
    }

    @Test
    fun `a generated token survives a shell command line and a URL`() {
        // Base64url, so no +, / or = to be re-encoded, quoted or truncated on the
        // way through `am broadcast --es token …`.
        val token = ApiTokens.generate()
        assertTrue(token, token.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun `a token matches itself and nothing else`() {
        val token = ApiTokens.generate()
        assertTrue(ApiTokens.matches(token, token))
        assertFalse(ApiTokens.matches(token, ApiTokens.generate()))
        assertFalse(ApiTokens.matches(token, token.dropLast(1)))
        assertFalse(ApiTokens.matches(token, token + "x"))
        assertFalse(ApiTokens.matches(token, token.uppercase() + token.lowercase()))
    }

    /**
     * The most important assertion in this file. A trigger with no key is not
     * "callable with no key" — it is not callable *by key* at all, only by an app
     * the user approved by name. Read the other way round, an unset field would
     * authorise every caller that thought to omit the extra.
     */
    @Test
    fun `a blank expected token never matches anything`() {
        assertFalse(ApiTokens.matches("", ""))
        assertFalse(ApiTokens.matches("", null))
        assertFalse(ApiTokens.matches("", "anything"))
        assertFalse(ApiTokens.matches("   ", "   "))
    }

    @Test
    fun `an absent or empty presented token never matches a real one`() {
        val token = ApiTokens.generate()
        assertFalse(ApiTokens.matches(token, null))
        assertFalse(ApiTokens.matches(token, ""))
    }
}
