package io.github.m1n1m1.easymatic.domain.model

import kotlinx.serialization.Serializable

/**
 * The colour a macro's icon is tinted with, wherever that icon appears.
 *
 * It tints the **glyph and its chip only**, never a surface. On a home-screen
 * widget the surface comes from the system (Material You on API 31+), so a
 * macro's own colour has to be the thing sitting *on* that surface or the two
 * would fight: a widget painted user-red on a wallpaper-blue home screen reads
 * as a foreign object, while a red glyph on a system-neutral card reads the way
 * a coloured label reads in Calendar or Keep.
 *
 * [SYSTEM] is the default and means "whatever the wallpaper accent is", so a
 * user who wants unbroken Material You gets it by doing nothing, and a fresh
 * macro never arrives wearing a colour nobody chose.
 *
 * ### Why there is a raw number here as well as two resource tables
 *
 * [argb] is a plain `0xAARRGGBB`, and **0 means [SYSTEM]** — "no fixed colour", the
 * same thing `MacroAccent.colorRes()` says with null. It exists because
 * `action.notify` hands an accent across
 * [io.github.m1n1m1.easymatic.core.service.Notifications], and `core` may not import
 * `domain`: a colour *resource* would arrive there as an anonymous `Int` that only
 * `data` could make sense of, so the number itself is what crosses, exactly as
 * `MessengerRecipe` carries strings rather than a `Messenger`.
 *
 * These are the **`values-night` values**, and that is a choice rather than an
 * oversight. `setColor` tints the small icon and the app name in the notification
 * shade, and a shade is dark on most phones out of the box; the darker daytime
 * values disappear against it, while these stay legible on a light shade too. A
 * notification is also not a surface this app draws, so there is no configuration
 * to resolve a day/night qualifier against at build time.
 */
@Serializable
@Suppress("MagicNumber") // A colour table is data; naming each hex value would say nothing the name does not.
enum class MacroAccent(val argb: Long) {
    SYSTEM(0),
    RED(0xFFE5534B),
    ORANGE(0xFFE06C4F),
    AMBER(0xFFE0A04C),
    GREEN(0xFF6FCF71),
    TEAL(0xFF56C2A8),
    BLUE(0xFF5B8DEF),
    VIOLET(0xFFC58AF9),
    PINK(0xFFE573B8),
}
