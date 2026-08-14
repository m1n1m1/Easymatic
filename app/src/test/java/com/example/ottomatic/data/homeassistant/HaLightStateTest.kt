package com.example.ottomatic.data.homeassistant

import com.example.ottomatic.core.service.LightCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a light back.
 *
 * The brightness conversion is the second unit trap in this integration, and the
 * reachability one is what `LightReading`'s KDoc exists for: an `action.if` has to be
 * able to tell "the lamp is off" from "the lamp is not answering".
 */
class HaLightStateTest {

    private fun state(entityId: String, raw: String, attributes: String = "{}") =
        HaResources.parseState(
            """{"entity_id": "$entityId", "state": "$raw", "attributes": $attributes}""",
        )!!

    /**
     * Home Assistant's `brightness` is **0–255** where the facade speaks a percentage.
     * Passed through unconverted, a lamp at half brightness reports 127 %, and a
     * comparison against `> 80` then reads as "nearly full" for every lamp above about
     * a third.
     */
    @Test
    fun `brightness is converted from 0-255 to a percentage`() {
        val full = HaLightState.readingOf(state("light.a", "on", """{"brightness": 255}"""))
        val half = HaLightState.readingOf(state("light.a", "on", """{"brightness": 128}"""))

        assertEquals(100.0, full.brightnessPercent, 0.01)
        assertEquals(50.2, half.brightnessPercent, 0.1)
    }

    @Test
    fun `a light with no brightness attribute reads as zero rather than throwing`() {
        assertEquals(0.0, HaLightState.readingOf(state("light.a", "off")).brightnessPercent, 0.01)
    }

    @Test
    fun `a colour is read back as a single sRGB value`() {
        val reading = HaLightState.readingOf(state("light.a", "on", """{"rgb_color": [255, 167, 87]}"""))

        assertEquals(0xFFA757, reading.colourRgb)
    }

    /**
     * Absent is the normal case rather than an error: a colour bulb sitting in white
     * mode reports no `rgb_color` at all, and neither does a white-only one. The same
     * sentinel a caller sends for "no colour", so the two agree.
     */
    @Test
    fun `a light showing no colour answers the not-given sentinel`() {
        assertEquals(
            LightCommand.NO_COLOUR,
            HaLightState.readingOf(state("light.a", "on")).colourRgb,
        )
        assertEquals(
            LightCommand.NO_COLOUR,
            HaLightState.readingOf(state("light.a", "on", """{"rgb_color": [1, 2]}""")).colourRgb,
        )
    }

    /**
     * The distinction `LightReading` exists to preserve. `unavailable` is a device that
     * dropped off the network and `unknown` is one Home Assistant has never heard from;
     * reporting either as "off" would let a macro conclude the lamp is off and switch it
     * on for ever.
     */
    @Test
    fun `unreachable is not the same as off`() {
        val unavailable = HaLightState.readingOf(state("light.a", "unavailable"))
        val off = HaLightState.readingOf(state("light.a", "off"))

        assertFalse(unavailable.on)
        assertFalse(unavailable.reachable)
        assertFalse(off.on)
        assertTrue(off.reachable)
        assertFalse(HaLightState.isReachable("unknown"))
    }

    /**
     * "Is the kitchen on?" means "is anything in it lit?", and the brightness is the
     * mean over the **lit** members — averaging in the dark ones would report a room
     * with one lamp at full brightness as being at 25 %.
     */
    @Test
    fun `an area is on when any member is and averages only the lit ones`() {
        val reading = HaLightState.groupReadingOf(
            "Kitchen",
            listOf(
                state("light.a", "on", """{"brightness": 255}"""),
                state("light.b", "off"),
                state("light.c", "off"),
            ),
        )

        assertTrue(reading.on)
        assertEquals(100.0, reading.brightnessPercent, 0.01)
    }

    /**
     * A room with a red lamp and a white one has no colour, and inventing one would be
     * a reading an `action.if` could act on. Reported honestly rather than guessed at,
     * exactly as a Hue group is.
     */
    @Test
    fun `an area reports no colour and no warmth`() {
        val reading = HaLightState.groupReadingOf(
            "Kitchen",
            listOf(state("light.a", "on", """{"rgb_color": [255, 0, 0], "color_temp_kelvin": 2700}""")),
        )

        assertEquals(LightCommand.NO_COLOUR, reading.colourRgb)
        assertEquals(0, reading.kelvin)
    }

    @Test
    fun `an area whose every lamp has dropped off is unreachable`() {
        val reading = HaLightState.groupReadingOf(
            "Kitchen",
            listOf(state("light.a", "unavailable"), state("light.b", "unavailable")),
        )

        assertFalse(reading.reachable)
        assertFalse(reading.on)
    }
}
