package io.github.m1n1m1.easymatic.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a tag's hardware id becomes text, how it is shown, and which ids are not
 * worth saving.
 */
class NfcTagIdTest {

    @Test
    fun `an id is uppercase hex with no separators`() {
        assertEquals("04A23F1B", NfcTagId.format(byteArrayOf(0x04, 0xA2.toByte(), 0x3F, 0x1B)))
    }

    /** A leading zero byte is two characters, not one — 7-byte ids start 0x04 often enough. */
    @Test
    fun `every byte is two characters`() {
        assertEquals("000F", NfcTagId.format(byteArrayOf(0x00, 0x0F)))
        assertEquals("FF", NfcTagId.format(byteArrayOf(0xFF.toByte())))
    }

    /**
     * Blank means "this tag cannot be identified", the same convention
     * [WifiSsid.normalise] uses for a network the platform will not name.
     */
    @Test
    fun `no id at all reads as blank`() {
        assertEquals("", NfcTagId.format(null))
        assertEquals("", NfcTagId.format(byteArrayOf()))
    }

    @Test
    fun `display groups bytes so an id can be read off the screen`() {
        assertEquals("04:A2:3F:1B", NfcTagId.display("04A23F1B"))
        assertEquals("", NfcTagId.display(""))
    }

    /**
     * A 4-byte id beginning 0x08 is a random id by ISO/IEC 14443-3 — a bank card, a
     * phone emulating one, or DESFire in random mode. Saving one is pointless, and
     * without this the user finds out weeks later when the macro stops firing.
     */
    @Test
    fun `a four-byte id starting 08 is random`() {
        assertTrue(NfcTagId.isUnstable("08A1B2C3"))
        // Seven bytes starting 08 is a real fixed id: the rule is single-size only.
        assertFalse(NfcTagId.isUnstable("08A1B2C3D4E5F6"))
        // 04 is the commonest NXP prefix and is exactly what a sticker looks like.
        assertFalse(NfcTagId.isUnstable("04A23F1B"))
    }

    /** An NfcB tag regenerates its identifier on every activation, by design. */
    @Test
    fun `an NfcB tag is unstable whatever its id looks like`() {
        assertTrue(NfcTagId.isUnstable("04A23F1B", listOf("android.nfc.tech.NfcB")))
        assertFalse(NfcTagId.isUnstable("04A23F1B", listOf("android.nfc.tech.NfcA")))
    }

    @Test
    fun `an id that is not there is unstable, since nothing can match it`() {
        assertTrue(NfcTagId.isUnstable(""))
    }

    /** Blank configured means any tag, exactly as a blank SSID means any network. */
    @Test
    fun `a trigger with no tag chosen matches every tag`() {
        assertTrue(NfcTagId.matches("", "04A23F1B"))
        assertTrue(NfcTagId.matches("04A23F1B", "04A23F1B"))
        assertFalse(NfcTagId.matches("04A23F1B", "0BADC0DE"))
    }
}
