package com.example.ottomatic.data.homeassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OAuth exchange's pure halves.
 *
 * The token endpoint's real behaviour cannot be arranged from a test — a refused grant,
 * a refresh that returns no new refresh token, an expiry the server chose — and every one
 * of those is a shape this code has to read correctly or sign the user out at a moment
 * nothing connects to the cause.
 */
class HaOAuthTest {

    /**
     * The gate that keeps a path which cannot work from shipping as a button that fails.
     * Home Assistant fetches [HaOAuth.CLIENT_ID] during the authorize step, so with no
     * page behind it the failure arrives in a browser, worded by Home Assistant, about a
     * URL the user has never heard of.
     */
    @Test
    fun `signing in is unavailable until a client page is hosted`() {
        assertEquals(HaOAuth.CLIENT_ID.isNotBlank(), HaOAuth.isAvailable)
        if (!HaOAuth.isAvailable) {
            assertNull(HaOAuth.authorizeRequest("http://homeassistant.local:8123"))
        }
    }

    @Test
    fun `an unusable address yields no authorize request`() {
        // Scheme-less, which HaBaseUrl refuses rather than guessing at.
        assertNull(HaOAuth.authorizeRequest("homeassistant.local:8123"))
        assertNull(HaOAuth.authorizeRequest(""))
    }

    @Test
    fun `the grant bodies name the grant type and the client`() {
        val code = HaOAuth.codeExchangeBody("abc 123")
        val refresh = HaOAuth.refreshBody("r/e+f")

        assertTrue(code.startsWith("grant_type=authorization_code"))
        // Percent-encoded, so a code containing a space or a plus survives the form body.
        assertTrue(code.contains("code=abc+123"))
        assertTrue(refresh.startsWith("grant_type=refresh_token"))
        assertTrue(refresh.contains("refresh_token=r%2Fe%2Bf"))
    }

    @Test
    fun `a token response is read whole`() {
        val tokens = HaOAuth.readTokens(
            """{"access_token":"at","refresh_token":"rt","expires_in":1800,"token_type":"Bearer"}""",
        )!!

        assertEquals("at", tokens.accessToken)
        assertEquals("rt", tokens.refreshToken)
        assertEquals(1_800, tokens.expiresInSeconds)
    }

    /**
     * The normal case on a renewal, and the one that would sign the user out hours later
     * if it were read as a failure: a refresh response carries a new access token and no
     * new refresh token, because the existing one goes on being valid.
     */
    @Test
    fun `a refresh response carries no new refresh token and that is not a failure`() {
        val tokens = HaOAuth.readTokens("""{"access_token":"at2","expires_in":1800}""")!!

        assertEquals("at2", tokens.accessToken)
        assertEquals("", tokens.refreshToken)
    }

    @Test
    fun `a response with no access token is nothing`() {
        assertNull(HaOAuth.readTokens("""{"error":"invalid_grant"}"""))
        assertNull(HaOAuth.readTokens("not json"))
        assertNull(HaOAuth.readTokens(""))
    }

    /**
     * The server's own sentence is the only one a user can act on — "The authorization
     * code is no longer valid" says what to do where a bare 400 does not.
     */
    @Test
    fun `a refusal keeps the servers explanation`() {
        assertEquals(
            "Invalid client id",
            HaOAuth.errorOf("""{"error":"invalid_request","error_description":"Invalid client id"}"""),
        )
        // Falls back to the code when there is no description.
        assertEquals("invalid_grant", HaOAuth.errorOf("""{"error":"invalid_grant"}"""))
        assertNull(HaOAuth.errorOf("""{"ok":true}"""))
        assertNull(HaOAuth.errorOf("<html>"))
    }

    /**
     * A minute is taken off so a request sent as the token expires renews first rather
     * than failing and being retried.
     */
    @Test
    fun `expiry is stamped a minute early`() {
        assertEquals(1_000_000L + 1_800_000L - 60_000L, HaOAuth.expiryOf(1_000_000L, 1_800))
    }

    /**
     * Zero means "does not expire", which is what `HaTokens` reads it as — so a response
     * that named no lifetime does not become a token treated as already stale.
     */
    @Test
    fun `a response with no lifetime never expires`() {
        assertEquals(0L, HaOAuth.expiryOf(1_000_000L, 0))
        assertEquals(0L, HaOAuth.expiryOf(1_000_000L, -1))
    }

    /**
     * The redirect the manifest catches and the `<link rel="redirect_uri">` on the client
     * page have to agree exactly, or Home Assistant refuses the authorize request. This
     * pins the string that appears in three places.
     */
    @Test
    fun `the redirect uri is the one the manifest registers`() {
        assertEquals("ottomatic://ha-auth", HaOAuth.REDIRECT_URI)
        assertFalse(HaOAuth.REDIRECT_URI.endsWith("/"))
    }
}
