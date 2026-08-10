package com.example.ottomatic.data.hue

import com.example.ottomatic.core.service.LightCommand
import com.example.ottomatic.core.service.LightOp
import com.example.ottomatic.core.service.SmartHomeTargetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exact bytes that reach the bridge.
 *
 * This is the half of the integration that fails *silently*: a bridge accepts a
 * request it does not act on and answers 200, so a body that is subtly wrong looks
 * from every direction like a node that ran and did nothing.
 */
class HueCommandsTest {

    private fun command(
        op: LightOp,
        rgb: Int = -1,
        kelvin: Int = 0,
        brightness: Int = 100,
        onlyIfOn: Boolean = false,
    ) = LightCommand(
        hubId = "hub",
        kind = SmartHomeTargetKind.LIGHT,
        rid = "rid",
        op = op,
        brightnessPercent = brightness,
        colourRgb = rgb,
        kelvin = kelvin,
        transitionMs = 400,
        onlyIfOn = onlyIfOn,
    )

    /**
     * A room is controlled through its `grouped_light` service, so it is a different
     * path with the same body — which is exactly why the snapshot stores the
     * service's rid rather than the room's.
     */
    @Test
    fun `each kind of target has its own path`() {
        assertEquals("/clip/v2/resource/light/r1", HueCommands.pathFor(SmartHomeTargetKind.LIGHT, "r1"))
        assertEquals("/clip/v2/resource/grouped_light/r1", HueCommands.pathFor(SmartHomeTargetKind.GROUP, "r1"))
        assertEquals("/clip/v2/resource/scene/r1", HueCommands.pathFor(SmartHomeTargetKind.SCENE, "r1"))
    }

    @Test
    fun `on and off write the on resource`() {
        assertTrue(HueCommands.bodyFor(command(LightOp.TURN_ON)).contains("\"on\":{\"on\":true}"))
        assertTrue(HueCommands.bodyFor(command(LightOp.TURN_OFF)).contains("\"on\":{\"on\":false}"))
    }

    /**
     * The one that has to ride along. A bridge will store a dimming level on a light
     * that is off, light nothing, and report success — and "Set brightness to 30 %"
     * means "have this light on, at 30 %" to everybody who has ever said it.
     */
    @Test
    fun `setting a brightness also turns the light on`() {
        val body = HueCommands.bodyFor(command(LightOp.SET_BRIGHTNESS, brightness = 30))

        assertTrue(body.contains("\"on\":{\"on\":true}"))
        assertTrue(body.contains("\"dimming\":{\"brightness\":30.0}"))
    }

    /**
     * The opposite of the rule above, and the reason it is a rule rather than a
     * default: when the caller has already narrowed down to lights that *are* lit,
     * sending an `on` again would be claiming something about a switch it was
     * explicitly told not to touch.
     */
    @Test
    fun `a value left to lit lights carries no switch at all`() {
        val body = HueCommands.bodyFor(command(LightOp.SET_BRIGHTNESS, brightness = 30, onlyIfOn = true))

        assertTrue(body, !body.contains("\"on\""))
        assertTrue(body.contains("\"dimming\":{\"brightness\":30.0}"))

        val colour = HueCommands.bodyFor(command(LightOp.SET_COLOUR, rgb = 0xFF0000, onlyIfOn = true))
        assertTrue(colour, !colour.contains("\"on\""))
    }

    /** An explicit switch is never suppressed: skipping it would leave nothing to do. */
    @Test
    fun `an explicit on or off still carries its switch`() {
        assertTrue(HueCommands.bodyFor(command(LightOp.TURN_ON, onlyIfOn = true)).contains("\"on\":{\"on\":true}"))
        assertTrue(HueCommands.bodyFor(command(LightOp.TURN_OFF, onlyIfOn = true)).contains("\"on\":{\"on\":false}"))
    }

    @Test
    fun `a colour is sent as chromaticity and a warmth as mireds`() {
        val colour = HueCommands.bodyFor(command(LightOp.SET_COLOUR, rgb = 0xFF0000))
        assertTrue(colour.contains("\"color\":{\"xy\":{\"x\":0.7"))

        val warmth = HueCommands.bodyFor(command(LightOp.SET_TEMPERATURE, kelvin = 2700))
        assertTrue(warmth.contains("\"color_temperature\":{\"mirek\":370}"))
    }

    /**
     * The v1 API called this `transitiontime` and counted it in tenths of a second.
     * Sending that name here is accepted and ignored, which reads as "the fade
     * setting does nothing".
     */
    @Test
    fun `the fade is a dynamics duration and not v1's transition time`() {
        val body = HueCommands.bodyFor(command(LightOp.TURN_ON))

        assertTrue(body.contains("\"dynamics\":{\"duration\":400}"))
        assertTrue(!body.contains("transitiontime"))
    }

    /**
     * There is no toggle on the bridge, so the caller reads the current state and
     * sends the inverse. This pins that a toggle reaching here — which it should not
     * — writes something coherent rather than an empty body.
     */
    @Test
    fun `a toggle that reaches the builder writes an on`() {
        assertTrue(HueCommands.bodyFor(command(LightOp.TOGGLE)).contains("\"on\":{\"on\":true}"))
    }

    @Test
    fun `a scene is recalled with its own body`() {
        val body = HueCommands.sceneBody(transitionMs = 1_500)

        assertTrue(body.contains("\"recall\":{\"action\":\"active\",\"duration\":1500}"))
    }

    /**
     * What turns a scene *off*: the group behind it, switched with nothing else in
     * the body. Sending a dimming or a colour along with it would half-apply the
     * scene on the way out.
     */
    @Test
    fun `switching a group carries nothing but the switch and the fade`() {
        val off = HueCommands.onOffBody(on = false, transitionMs = 400)

        assertEquals("{\"on\":{\"on\":false},\"dynamics\":{\"duration\":400}}", off)
        assertTrue(HueCommands.onOffBody(on = true, transitionMs = 0).contains("\"on\":{\"on\":true}"))
    }
}
