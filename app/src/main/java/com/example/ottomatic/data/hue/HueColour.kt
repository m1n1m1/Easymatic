package com.example.ottomatic.data.hue

import kotlin.math.pow

/**
 * The bridge's colour arithmetic, and the reason the facade above it never mentions
 * any of it.
 *
 * `SmartHome` speaks sRGB and kelvin, which is what a person means. A bridge speaks
 * CIE xy chromaticity and *mireds* — reciprocal megakelvin, where a larger number is
 * a warmer light. Translating between the two is the vendor's business, so it lives
 * here, in `data/hue/`, where a second vendor's arithmetic can sit beside it without
 * either leaking upward.
 *
 * Pure functions with no Android import, so they are tested on the JVM — which
 * matters more here than it looks, because every mistake in this file produces a
 * light that turns on in *some* colour, and nobody can tell by looking whether the
 * matrix is right.
 *
 * The matrix is the vendor's own wide-gamut one rather than the textbook sRGB→XYZ
 * matrix. It is not normalised: white comes out near (0.308, 0.339) rather than at
 * D65's (0.3127, 0.3290), which is the published behaviour and not an error to be
 * "fixed" — the bridge expects coordinates in this space.
 */
internal object HueColour {

    /** [rgb] as `0xRRGGBB`, in the chromaticity coordinates the bridge accepts. */
    fun xyFromRgb(rgb: Int): Pair<Double, Double> {
        val red = linear((rgb shr RED_SHIFT and BYTE_MASK) / BYTE_MAX)
        val green = linear((rgb shr GREEN_SHIFT and BYTE_MASK) / BYTE_MAX)
        val blue = linear((rgb and BYTE_MASK) / BYTE_MAX)
        val x = red * X_FROM_R + green * X_FROM_G + blue * X_FROM_B
        val y = red * Y_FROM_R + green * Y_FROM_G + blue * Y_FROM_B
        val z = green * Z_FROM_G + blue * Z_FROM_B
        val sum = x + y + z
        // Black has no chromaticity at all. Answering the white point rather than a
        // division by zero keeps this total, which is what lets the caller stay simple.
        return if (sum <= 0.0) WHITE_POINT else Pair(x / sum, y / sum)
    }

    /**
     * Chromaticity plus a brightness back into `0xRRGGBB`.
     *
     * Used only on the read path, so that `action.light_state` can answer a colour
     * in the same hex the Control Light field takes. The round trip is lossy — the
     * bridge's gamut is not sRGB's, so a saturated colour comes back clipped — and
     * that is acceptable for a value that exists to be compared and displayed.
     */
    fun rgbFromXy(x: Double, y: Double, brightnessPercent: Double): Int {
        if (y <= 0.0) return 0
        val luminance = (brightnessPercent / PERCENT).coerceIn(0.0, 1.0)
        val bigX = (luminance / y) * x
        val bigZ = (luminance / y) * (1.0 - x - y)
        val red = bigX * R_FROM_X + luminance * R_FROM_Y + bigZ * R_FROM_Z
        val green = bigX * G_FROM_X + luminance * G_FROM_Y + bigZ * G_FROM_Z
        val blue = bigX * B_FROM_X + luminance * B_FROM_Y + bigZ * B_FROM_Z
        // One shared divisor when any channel overflows, so an out-of-gamut colour is
        // desaturated towards white rather than having its hue shifted by clipping.
        val peak = maxOf(red, green, blue, 1.0)
        return (channel(red / peak) shl RED_SHIFT) or (channel(green / peak) shl GREEN_SHIFT) or channel(blue / peak)
    }

    /**
     * Kelvin as the bridge's mireds, clamped to what it can actually render.
     *
     * Clamping rather than refusing: 1 000 K is a coherent thing to ask for and no
     * bulb on the market does it, so the honest answer is the warmest it has. The
     * node says when it clamped.
     */
    fun mirekFromKelvin(kelvin: Int): Int =
        if (kelvin <= 0) MIREK_MAX else (MIREK_SCALE / kelvin).coerceIn(MIREK_MIN, MIREK_MAX)

    /** The reverse, for the read path. */
    fun kelvinFromMirek(mirek: Int): Int = if (mirek <= 0) 0 else MIREK_SCALE / mirek

    /** Whether [kelvin] is outside what the bridge renders, so the node can say so. */
    fun isOutOfRange(kelvin: Int): Boolean = kelvin > 0 && mirekFromKelvin(kelvin) != MIREK_SCALE / kelvin

    private fun linear(component: Double): Double =
        if (component > GAMMA_THRESHOLD) {
            ((component + GAMMA_OFFSET) / GAMMA_SCALE).pow(GAMMA_EXPONENT)
        } else {
            component / GAMMA_SLOPE
        }

    private fun channel(component: Double): Int {
        val clamped = component.coerceIn(0.0, 1.0)
        val encoded = if (clamped <= INVERSE_GAMMA_THRESHOLD) {
            GAMMA_SLOPE * clamped
        } else {
            GAMMA_SCALE * clamped.pow(1.0 / GAMMA_EXPONENT) - GAMMA_OFFSET
        }
        return (encoded * BYTE_MAX).toInt().coerceIn(0, BYTE_MASK)
    }

    private val WHITE_POINT = Pair(0.3227, 0.3290)

    private const val BYTE_MASK = 0xFF
    private const val BYTE_MAX = 255.0
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val PERCENT = 100.0

    private const val GAMMA_THRESHOLD = 0.04045
    private const val INVERSE_GAMMA_THRESHOLD = 0.0031308
    private const val GAMMA_OFFSET = 0.055
    private const val GAMMA_SCALE = 1.055
    private const val GAMMA_SLOPE = 12.92
    private const val GAMMA_EXPONENT = 2.4

    private const val X_FROM_R = 0.649926
    private const val X_FROM_G = 0.103455
    private const val X_FROM_B = 0.197109
    private const val Y_FROM_R = 0.234327
    private const val Y_FROM_G = 0.743075
    private const val Y_FROM_B = 0.068414
    private const val Z_FROM_G = 0.053077
    private const val Z_FROM_B = 1.035763

    private const val R_FROM_X = 1.4628067
    private const val R_FROM_Y = -0.1840623
    private const val R_FROM_Z = -0.2743606
    private const val G_FROM_X = -0.5217933
    private const val G_FROM_Y = 1.4472381
    private const val G_FROM_Z = 0.0677227
    private const val B_FROM_X = 0.0349342
    private const val B_FROM_Y = -0.0968930
    private const val B_FROM_Z = 1.2884099

    /** Reciprocal megakelvin: mired = 1 000 000 / K. */
    private const val MIREK_SCALE = 1_000_000

    /** 6500 K, the coolest the bridge renders. */
    private const val MIREK_MIN = 153

    /** 2000 K, the warmest. */
    private const val MIREK_MAX = 500
}
