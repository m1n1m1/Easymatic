package com.example.ottomatic.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a typed broker address means.
 *
 * The three URL readers in `domain` disagree about a scheme-less host on purpose, so the
 * first two tests here are the ones that would catch somebody "simplifying" this into a
 * call to one of the others: [WebUrl] would make `192.168.1.5` an https URL and
 * [AiBaseUrl] would refuse it, and both are wrong for a field whose commonest possible
 * answer is exactly that.
 */
class MqttAddressTest {

    @Test
    fun `a bare host is the default plaintext broker port`() {
        val parsed = MqttAddress.parse("192.168.1.5")
        assertEquals("tcp://192.168.1.5:1883", parsed?.serverUri)
        assertEquals(1883, parsed?.port)
        assertTrue(parsed?.isCleartext == true)
    }

    @Test
    fun `a host with a port keeps the port and still gains the default scheme`() {
        assertEquals("tcp://broker.local:1884", MqttAddress.parse("broker.local:1884")?.serverUri)
    }

    @Test
    fun `the schemes people type are translated into the ones the client speaks`() {
        assertEquals("tcp://a.example:1883", MqttAddress.parse("mqtt://a.example")?.serverUri)
        assertEquals("ssl://a.example:8883", MqttAddress.parse("mqtts://a.example")?.serverUri)
        // Already in the library's own spelling: passed through rather than refused.
        assertEquals("tcp://a.example:1883", MqttAddress.parse("tcp://a.example")?.serverUri)
        assertEquals("ssl://a.example:8883", MqttAddress.parse("ssl://a.example")?.serverUri)
    }

    @Test
    fun `mqtts is not cleartext and mqtt is`() {
        assertFalse(MqttAddress.parse("mqtts://a.example")!!.isCleartext)
        assertFalse(MqttAddress.parse("wss://a.example/mqtt")!!.isCleartext)
        assertTrue(MqttAddress.parse("mqtt://a.example")!!.isCleartext)
        assertTrue(MqttAddress.parse("ws://a.example:9001/mqtt")!!.isCleartext)
    }

    @Test
    fun `a websocket path is kept, because that is where the endpoint lives`() {
        assertEquals("ws://a.example:9001/mqtt", MqttAddress.parse("ws://a.example:9001/mqtt")?.serverUri)
    }

    /**
     * The mistake this field will most often see: pasting the broker's *web dashboard*
     * address in. Treating the unknown scheme as part of a hostname would connect to
     * something that answers HTTP and time out saying nothing about why.
     */
    @Test
    fun `an http address is refused rather than read as a host`() {
        assertNull(MqttAddress.parse("http://broker.local:8080"))
        assertNull(MqttAddress.parse("https://broker.local"))
    }

    @Test
    fun `an unbracketed IPv6 literal is refused rather than read as a host and port`() {
        // "::1" would otherwise parse as the host ":" on port 1 — an address that looks
        // fine on the row and connects to nothing.
        assertNull(MqttAddress.parse("::1"))
        assertNull(MqttAddress.parse("fe80::1:1883"))
        assertEquals("tcp://[::1]:1883", MqttAddress.parse("[::1]")?.serverUri)
        assertEquals("tcp://[::1]:1884", MqttAddress.parse("[::1]:1884")?.serverUri)
    }

    @Test
    fun `nothing usable is refused`() {
        assertNull(MqttAddress.parse(""))
        assertNull(MqttAddress.parse("   "))
        assertNull(MqttAddress.parse("mqtt://"))
        assertNull(MqttAddress.parse("broker local"))
        assertNull(MqttAddress.parse("broker.local:not-a-port"))
        assertNull(MqttAddress.parse("broker.local:0"))
        assertNull(MqttAddress.parse("broker.local:70000"))
    }

    /**
     * A warning that appears only once the text is valid is a warning that flickers as
     * somebody types, so anything unparseable counts as cleartext — which is also what
     * the field's default actually is.
     */
    @Test
    fun `an address that does not parse counts as cleartext`() {
        assertTrue(MqttAddress.isCleartext(""))
        assertTrue(MqttAddress.isCleartext("mqtts:/"))
    }

    @Test
    fun `surrounding whitespace is forgiven, since it comes with every paste`() {
        assertEquals("tcp://a.example:1883", MqttAddress.parse("  a.example  ")?.serverUri)
    }
}
