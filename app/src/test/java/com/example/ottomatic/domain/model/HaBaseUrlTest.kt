package com.example.ottomatic.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a typed Home Assistant address means.
 *
 * The refusal cases are the ones that matter most, and not for tidiness: this field
 * holds the address a **long-lived access token** is sent to, so guessing `http://` for
 * a scheme-less `mycloud.nabu.casa` would put that token on the wire in the clear with
 * nothing anywhere saying so.
 */
class HaBaseUrlTest {

    @Test
    fun `an address with a scheme is kept as written`() {
        assertEquals("http://homeassistant.local:8123", HaBaseUrl.parse("http://homeassistant.local:8123"))
        assertEquals("https://abc.ui.nabu.casa", HaBaseUrl.parse("https://abc.ui.nabu.casa"))
        assertEquals("http://192.168.1.5:8123", HaBaseUrl.parse("  http://192.168.1.5:8123  "))
    }

    /**
     * The security case, stated as a test so nobody "improves" this into a default.
     * A port is not invented either: `:8123` is right for a default install and wrong
     * for every proxied one.
     */
    @Test
    fun `a scheme-less address is refused rather than guessed at`() {
        assertNull(HaBaseUrl.parse("homeassistant.local:8123"))
        assertNull(HaBaseUrl.parse("192.168.1.5:8123"))
        assertNull(HaBaseUrl.parse("abc.ui.nabu.casa"))
    }

    @Test
    fun `a scheme with no host is somebody halfway through typing`() {
        assertNull(HaBaseUrl.parse("http://"))
        assertNull(HaBaseUrl.parse("https://"))
        assertNull(HaBaseUrl.parse(""))
        assertNull(HaBaseUrl.parse("http://what ever:8123"))
    }

    /**
     * All three of these are things people actually paste: the docs give the websocket
     * path, this app's own errors give `/api`, and the browser address bar gives a
     * Lovelace path.
     */
    @Test
    fun `a pasted path is trimmed back to the origin`() {
        val expected = "http://homeassistant.local:8123"

        assertEquals(expected, HaBaseUrl.parse("http://homeassistant.local:8123/"))
        assertEquals(expected, HaBaseUrl.parse("http://homeassistant.local:8123/api"))
        assertEquals(expected, HaBaseUrl.parse("http://homeassistant.local:8123/api/websocket"))
        assertEquals(expected, HaBaseUrl.parse("http://homeassistant.local:8123/lovelace/0"))
        assertEquals(expected, HaBaseUrl.parse("http://homeassistant.local:8123/?foo=bar"))
    }

    /**
     * A `wss` handshake sent to an `http` origin fails inside the TLS layer, so it
     * surfaces as a bare connection error with nothing in it about the scheme.
     */
    @Test
    fun `the websocket address follows the scheme it was given`() {
        assertEquals(
            "ws://homeassistant.local:8123/api/websocket",
            HaBaseUrl.wsUrl("http://homeassistant.local:8123"),
        )
        assertEquals(
            "wss://abc.ui.nabu.casa/api/websocket",
            HaBaseUrl.wsUrl("https://abc.ui.nabu.casa/"),
        )
        assertNull(HaBaseUrl.wsUrl("homeassistant.local:8123"))
    }

    @Test
    fun `a REST address is the origin plus the path`() {
        assertEquals(
            "http://homeassistant.local:8123/api/states",
            HaBaseUrl.apiUrl("http://homeassistant.local:8123/api", "/api/states"),
        )
        assertNull(HaBaseUrl.apiUrl("nonsense", "/api/states"))
    }

    @Test
    fun `cleartext is reported so the editor can warn without refusing`() {
        assertTrue(HaBaseUrl.isCleartext("http://homeassistant.local:8123"))
        assertTrue(HaBaseUrl.isCleartext("HTTP://homeassistant.local:8123"))
        assertEquals(false, HaBaseUrl.isCleartext("https://abc.ui.nabu.casa"))
    }
}
