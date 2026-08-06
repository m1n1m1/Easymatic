package com.example.ottomatic.domain.model

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
 */
@Serializable
enum class MacroAccent {
    SYSTEM,
    RED,
    ORANGE,
    AMBER,
    GREEN,
    TEAL,
    BLUE,
    VIOLET,
    PINK,
}
