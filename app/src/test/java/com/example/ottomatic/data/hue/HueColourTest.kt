package com.example.ottomatic.data.hue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bridge's colour arithmetic.
 *
 * Worth testing more than most pure functions, because every mistake in it produces
 * a light that turns on in *some* colour: nothing reports an error, and nobody can
 * tell by looking whether the matrix is right. The expected values are the vendor's
 * own wide-gamut space, which is deliberately not normalised — white lands near
 * (0.31, 0.34) rather than at D65, and "fixing" that would send the bridge
 * coordinates it does not expect.
 */
class HueColourTest {

    @Test
    fun `the primaries land in the corners of the gamut`() {
        val (redX, redY) = HueColour.xyFromRgb(0xFF0000)
        assertNear(0.735, redX)
        assertNear(0.265, redY)

        val (greenX, greenY) = HueColour.xyFromRgb(0x00FF00)
        assertNear(0.115, greenX)
        assertNear(0.826, greenY)

        val (blueX, blueY) = HueColour.xyFromRgb(0x0000FF)
        assertNear(0.151, blueX)
        assertNear(0.053, blueY)
    }

    @Test
    fun `white lands on the matrix's own white point`() {
        val (x, y) = HueColour.xyFromRgb(0xFFFFFF)

        assertNear(0.308, x)
        assertNear(0.339, y)
    }

    /**
     * Black has no chromaticity at all, and the sum it would be divided by is zero.
     * Answering the white point rather than a NaN is what keeps the conversion total
     * — the caller has already decided this is a colour and has nowhere to put a
     * failure.
     */
    @Test
    fun `black answers a colour rather than a division by zero`() {
        val (x, y) = HueColour.xyFromRgb(0x000000)

        assertTrue(x.isFinite() && y.isFinite())
        assertTrue(x > 0.0 && y > 0.0)
    }

    @Test
    fun `kelvin becomes mireds and clamps at both ends`() {
        assertEquals(370, HueColour.mirekFromKelvin(2700))
        // Cooler than the bridge renders: clamped to its coolest rather than refused.
        assertEquals(153, HueColour.mirekFromKelvin(10_000))
        // Warmer than it renders, likewise.
        assertEquals(500, HueColour.mirekFromKelvin(1_000))
        // Nothing configured: the warm end, which is what a lamp looks like.
        assertEquals(500, HueColour.mirekFromKelvin(0))
    }

    @Test
    fun `mireds come back as kelvin`() {
        assertEquals(2702, HueColour.kelvinFromMirek(370))
        assertEquals(0, HueColour.kelvinFromMirek(0))
    }

    /**
     * The node says when it clamped, so this is what tells it that it did. 2700 K
     * is renderable and 10 000 K is not.
     */
    @Test
    fun `a warmth the bridge cannot render is reported as out of range`() {
        assertTrue(HueColour.isOutOfRange(10_000))
        assertTrue(HueColour.isOutOfRange(1_000))
        assertTrue(!HueColour.isOutOfRange(2700))
    }

    @Test
    fun `a colour survives the round trip well enough to compare`() {
        val (x, y) = HueColour.xyFromRgb(0xFF0000)
        val back = HueColour.rgbFromXy(x, y, brightnessPercent = 100.0)

        // Lossy by construction — the bridge's gamut is not sRGB's — so the test is
        // that red comes back red, not that the bits match.
        assertTrue((back shr 16 and 0xFF) > (back shr 8 and 0xFF))
        assertTrue((back shr 16 and 0xFF) > (back and 0xFF))
    }

    private fun assertNear(expected: Double, actual: Double) {
        assertEquals(expected, actual, TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 0.005
    }
}
