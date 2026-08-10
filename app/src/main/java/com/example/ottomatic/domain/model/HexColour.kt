package com.example.ottomatic.domain.model

/**
 * How a colour typed into a node's config is read.
 *
 * A colour is a **plain text field** rather than a picker, and the line CLAUDE.md
 * draws is opacity: a mistyped UUID is indistinguishable from a correct one, where
 * `#FF8800` reads back and a wrong one is visibly wrong. It is also not a fifth
 * editable-with-a-chooser annotation, because a swatch grid does not clear the bar
 * `@WifiNetwork` sets for that shape — *a chooser earns it when it can only offer
 * what is reachable right now and the thing being configured usually is not*, and
 * every colour always is. Adding one later is pure addition and changes nothing
 * about the value stored here.
 *
 * Lenient in [WebUrl]'s style, and for the same reason: what somebody types is what
 * they mean. `#FF8800`, `FF8800`, `#F80`, `orange` and a stray space around any of
 * them are all the same colour. Anything else is `null` rather than a guess — the
 * node reports `Not a colour: "blu"` by name, where sending black would be a light
 * that turns on wrong with nothing said about why.
 *
 * In `domain` on [WebUrl]'s placement argument: a pure function with JVM tests,
 * shared by the node and by anything that later renders a swatch. The conversion
 * from sRGB into the bridge's own coordinate system is a different job and lives in
 * `data/hue/HueColour.kt`, because that one is the vendor's business.
 */
object HexColour {

    private const val HEX_RADIX = 16
    private const val SHORT_LENGTH = 3
    private const val FULL_LENGTH = 6
    private const val RGB_MASK = 0xFFFFFF

    /**
     * A handful of names, deliberately not a full CSS table.
     *
     * These are the ones somebody types at a light rather than at a stylesheet, and
     * the two that earn their place most are the last: "warm white" and "daylight"
     * are what the packaging on a bulb says, and neither has an obvious hex.
     */
    private val NAMED = mapOf(
        "red" to 0xFF0000,
        "orange" to 0xFF8000,
        "yellow" to 0xFFFF00,
        "green" to 0x00FF00,
        "cyan" to 0x00FFFF,
        "blue" to 0x0000FF,
        "purple" to 0x8000FF,
        "pink" to 0xFF40A0,
        "white" to 0xFFFFFF,
        "warm white" to 0xFFA757,
        "daylight" to 0xFFF4E8,
    )

    /** The sRGB value [text] names as `0xRRGGBB`, or null when it names no colour. */
    fun parse(text: String): Int? {
        val trimmed = text.trim()
        val digits = trimmed.removePrefix("#")
        val expanded = when (digits.length) {
            // `#F80` is three digits each meaning a doubled pair, exactly as CSS reads it.
            SHORT_LENGTH -> digits.map { "$it$it" }.joinToString("")
            FULL_LENGTH -> digits
            // Not a length any hex colour has. Left blank rather than refused here,
            // so that a name — which is any length at all — still gets its turn.
            else -> ""
        }
        return NAMED[trimmed.lowercase()]
            ?: expanded.toIntOrNull(HEX_RADIX)?.takeIf { it in 0..RGB_MASK }
    }

    /** [rgb] as the text a config field holds. */
    fun format(rgb: Int): String = "#%06X".format(rgb and RGB_MASK)
}
