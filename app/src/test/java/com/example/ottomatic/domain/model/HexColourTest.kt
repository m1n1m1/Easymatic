package com.example.ottomatic.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The colour field's reader.
 *
 * Leniency here is the same argument [WebUrl] makes: what somebody types is what
 * they mean, and `FF8800` without a hash is not a mistake. Totality is the other
 * half — the node reports `Not a colour: "blu"` by name rather than sending black,
 * and only a parser that answers null rather than guessing makes that distinction
 * real.
 */
class HexColourTest {

    @Test
    fun `the usual spellings all name the same colour`() {
        assertEquals(0xFF8800, HexColour.parse("#FF8800"))
        assertEquals(0xFF8800, HexColour.parse("FF8800"))
        assertEquals(0xFF8800, HexColour.parse("  #ff8800 "))
    }

    /** Three digits are doubled pairs, exactly as CSS reads them. */
    @Test
    fun `a short form expands the way a stylesheet would`() {
        assertEquals(0xFF8800, HexColour.parse("#F80"))
        assertEquals(0xFFFFFF, HexColour.parse("fff"))
    }

    /**
     * The two names that earn their place most are the ones printed on a bulb's
     * packaging: nobody knows the hex for "warm white".
     */
    @Test
    fun `a handful of names are accepted`() {
        assertEquals(0xFF0000, HexColour.parse("red"))
        assertEquals(0xFF0000, HexColour.parse(" RED "))
        assertEquals(0xFFA757, HexColour.parse("warm white"))
    }

    @Test
    fun `anything that is not a colour is refused rather than guessed at`() {
        assertNull(HexColour.parse(""))
        assertNull(HexColour.parse("blu"))
        assertNull(HexColour.parse("#GGGGGG"))
        assertNull(HexColour.parse("#FF88"))
        assertNull(HexColour.parse("#FF88000"))
    }

    @Test
    fun `formatting produces something parse accepts`() {
        assertEquals("#FF8800", HexColour.format(0xFF8800))
        assertEquals("#000000", HexColour.format(0))
        assertEquals(0xFF8800, HexColour.parse(HexColour.format(0xFF8800)))
    }
}
