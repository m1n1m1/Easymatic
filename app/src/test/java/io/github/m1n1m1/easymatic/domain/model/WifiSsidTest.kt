package io.github.m1n1m1.easymatic.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The platform's three disguises for a network name, and the one rule for matching
 * one against a configured field.
 */
class WifiSsidTest {

    @Test
    fun `the quotes WifiInfo wraps a name in are not part of the name`() {
        assertEquals("Office-5G", WifiSsid.normalise("\"Office-5G\""))
        assertEquals("Office-5G", WifiSsid.normalise("Office-5G"))
    }

    /**
     * The two non-answers, and the reason they must collapse to blank rather than
     * reach a config field: stored verbatim, `<unknown ssid>` is a perfectly valid
     * SSID string that would match a network nobody has, and compare equal to itself
     * across two different networks.
     */
    @Test
    fun `a withheld name is blank rather than a network called unknown`() {
        assertEquals("", WifiSsid.normalise("<unknown ssid>"))
        assertEquals("", WifiSsid.normalise("\"<unknown ssid>\""))
        assertEquals("", WifiSsid.normalise("<UNKNOWN SSID>"))
        assertEquals("", WifiSsid.normalise("02:00:00:00:00:00"))
    }

    @Test
    fun `nothing at all is blank`() {
        assertEquals("", WifiSsid.normalise(null))
        assertEquals("", WifiSsid.normalise(""))
        assertEquals("", WifiSsid.normalise("   "))
    }

    /** A colon in a name is only a placeholder when it is *the* placeholder. */
    @Test
    fun `a real name that looks like a mac address survives`() {
        assertEquals("02:00:00:00:00:01", WifiSsid.normalise("02:00:00:00:00:01"))
    }

    @Test
    fun `an unconfigured field matches every network`() {
        assertTrue(WifiSsid.matches(configured = "", actual = "Office-5G"))
        assertTrue(WifiSsid.matches(configured = "  ", actual = "Office-5G"))
        // Including one that could not be named — "any" really does mean any.
        assertTrue(WifiSsid.matches(configured = "", actual = ""))
    }

    @Test
    fun `a configured field matches only its own network`() {
        assertTrue(WifiSsid.matches(configured = "Office-5G", actual = "Office-5G"))
        assertFalse(WifiSsid.matches(configured = "Office-5G", actual = "Office-2G"))
        // A network that could not be named matches no filter, so the trigger that
        // set one fails closed rather than firing on everything.
        assertFalse(WifiSsid.matches(configured = "Office-5G", actual = ""))
    }

    /**
     * An SSID is a byte string rather than text, so two networks differing only in
     * case are two networks and can legally be in range at once. Matching them
     * loosely would silently act on the wrong one.
     */
    @Test
    fun `matching is case sensitive`() {
        assertFalse(WifiSsid.matches(configured = "MyWifi", actual = "mywifi"))
    }
}
