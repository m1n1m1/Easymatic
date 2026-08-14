package com.example.ottomatic.data.homeassistant

import com.example.ottomatic.core.service.LightCommand
import com.example.ottomatic.core.service.LightReading
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * One Home Assistant state, as the facade's [LightReading].
 *
 * Pure and tested for [HaCommands]' reason — every conversion here is a place a reading
 * can be wrong without anything saying so, and one of them is a genuine unit trap of
 * the same family as `transition`.
 */
internal object HaLightState {

    /** Whether a state string means the entity is switched on. */
    fun isOn(state: String): Boolean = state == ON

    /**
     * Whether a state string means Home Assistant can actually talk to the thing.
     *
     * `unavailable` is a device that has dropped off the network; `unknown` is one
     * Home Assistant has never heard from. Neither is "off", and reporting them as off
     * is the failure [LightReading]'s KDoc exists to prevent — an `action.if` must be
     * able to tell "the lamp is off" from "the lamp is not answering".
     */
    fun isReachable(state: String): Boolean = state !in UNREACHABLE

    /** One light's state as a reading. */
    fun readingOf(state: HaResources.HaState): LightReading = LightReading(
        found = true,
        name = state.friendlyName,
        on = isOn(state.state),
        brightnessPercent = brightnessPercentOf(state),
        colourRgb = colourOf(state),
        kelvin = state.attributes[COLOUR_TEMP_KELVIN]?.jsonPrimitive?.intOrNull ?: 0,
        reachable = isReachable(state.state),
    )

    /**
     * An area's aggregate reading, on the same terms Hue reports a group.
     *
     * `on` is **any member lit**, which is what "is the kitchen on?" means to anybody
     * who asks it, and brightness is the mean over the **lit** members rather than over
     * all of them — averaging in the dark ones would report a room with one lamp at
     * full brightness as being at 25 %.
     *
     * Colour and temperature are deliberately **not** aggregated and answer "not given":
     * a room with a red lamp and a white one has no colour, and inventing one would be a
     * reading an `action.if` could act on. [LightReading]'s KDoc already documents this
     * for Hue as a limitation reported honestly rather than guessed at; here it is a
     * choice made on the same grounds.
     */
    fun groupReadingOf(name: String, members: List<HaResources.HaState>): LightReading {
        val lit = members.filter { isOn(it.state) }
        return LightReading(
            found = true,
            name = name,
            on = lit.isNotEmpty(),
            brightnessPercent = if (lit.isEmpty()) 0.0 else lit.sumOf { brightnessPercentOf(it) } / lit.size,
            colourRgb = LightCommand.NO_COLOUR,
            kelvin = 0,
            // A group is not a device and has nothing to be unreachable; a room whose
            // every lamp has dropped off, though, is worth reporting as such.
            reachable = members.any { isReachable(it.state) },
        )
    }

    /**
     * Home Assistant's `brightness` is **0–255**, where the facade speaks a percentage.
     *
     * The second unit conversion in this integration and the same class of bug as
     * `transition`: passed through unconverted, a lamp at half brightness reports 127 %,
     * which a comparison against `> 80` then reads as "nearly full" for every lamp above
     * about a third. Kept as a `Double` with no rounding, on [LightReading]'s terms.
     */
    private fun brightnessPercentOf(state: HaResources.HaState): Double {
        val raw = state.attributes[BRIGHTNESS]?.jsonPrimitive?.doubleOrNull ?: return 0.0
        return raw * PERCENT / MAX_BRIGHTNESS
    }

    /**
     * `rgb_color` as `0xRRGGBB`, or [LightCommand.NO_COLOUR] for a light that is not
     * showing one.
     *
     * Absent is the normal case rather than an error: a colour bulb sitting in white
     * mode reports no `rgb_color` at all, and neither does a white-only one. The same
     * sentinel the caller would have sent for "no colour", so a light with no gamut and
     * a caller that asked for none say the same thing.
     */
    private fun colourOf(state: HaResources.HaState): Int = runCatching {
        val rgb = state.attributes[RGB_COLOUR]?.jsonArray ?: return LightCommand.NO_COLOUR
        if (rgb.size < RGB_LENGTH) return LightCommand.NO_COLOUR
        val red = rgb[0].jsonPrimitive.intOrNull ?: return LightCommand.NO_COLOUR
        val green = rgb[1].jsonPrimitive.intOrNull ?: return LightCommand.NO_COLOUR
        val blue = rgb[2].jsonPrimitive.intOrNull ?: return LightCommand.NO_COLOUR
        (red shl RED_SHIFT) or (green shl GREEN_SHIFT) or blue
    }.getOrDefault(LightCommand.NO_COLOUR)

    private const val ON = "on"
    private val UNREACHABLE = setOf("unavailable", "unknown")
    private const val BRIGHTNESS = "brightness"
    private const val RGB_COLOUR = "rgb_color"
    private const val COLOUR_TEMP_KELVIN = "color_temp_kelvin"
    private const val MAX_BRIGHTNESS = 255.0
    private const val PERCENT = 100.0
    private const val RGB_LENGTH = 3
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
}
