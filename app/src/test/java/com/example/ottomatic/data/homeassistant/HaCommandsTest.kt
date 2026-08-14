package com.example.ottomatic.data.homeassistant

import com.example.ottomatic.core.service.LightCommand
import com.example.ottomatic.core.service.LightOp
import com.example.ottomatic.core.service.SceneOp
import com.example.ottomatic.core.service.SceneRecall
import com.example.ottomatic.core.service.SmartHomeTargetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The service calls, pinned.
 *
 * `HueCommandsTest`'s counterpart, and it exists for a sharper version of the same
 * reason: Home Assistant answers `{"success": true}` to a service call it carried out
 * against nothing at all. A misspelled attribute is dropped, an entity id that names
 * nothing is not found, and **neither is reported** — so every mistake in this file
 * produces a node that reports success and changes nothing.
 */
class HaCommandsTest {

    @Suppress("LongParameterList") // One per field of LightCommand a test varies; the facade sets the count.
    private fun command(
        op: LightOp,
        rid: String = "light.desk_lamp",
        kind: SmartHomeTargetKind = SmartHomeTargetKind.LIGHT,
        brightness: Int = 100,
        colour: Int = 0xFFA757,
        kelvin: Int = 2700,
        transitionMs: Int = 400,
        onlyIfOn: Boolean = false,
    ) = LightCommand(
        hubId = "hub-1",
        kind = kind,
        rid = rid,
        op = op,
        brightnessPercent = brightness,
        colourRgb = colour,
        kelvin = kelvin,
        transitionMs = transitionMs,
        onlyIfOn = onlyIfOn,
    )

    /**
     * **The unit trap**, and the single most valuable assertion in this file. The facade
     * speaks milliseconds, as every other duration in the app does, and Home Assistant
     * counts this one in seconds. Passed through unconverted, the 400 ms default becomes
     * a six-and-a-half-minute fade — which does not read as a unit bug, it reads as the
     * light never having changed.
     */
    @Test
    fun `the fade is sent in seconds and not milliseconds`() {
        val call = HaCommands.callFor(command(LightOp.TURN_ON, transitionMs = 400))

        assertTrue(call.data.contains("\"transition\":0.4"))
        assertFalse(call.data.contains("400"))
    }

    @Test
    fun `a whole-second fade keeps its fraction-free form`() {
        val call = HaCommands.callFor(command(LightOp.TURN_ON, transitionMs = 2_000))

        assertTrue(call.data.contains("\"transition\":2.0"))
    }

    @Test
    fun `on off and toggle are their own services`() {
        assertEquals("turn_on", HaCommands.callFor(command(LightOp.TURN_ON)).service)
        assertEquals("turn_off", HaCommands.callFor(command(LightOp.TURN_OFF)).service)
        assertEquals("light", HaCommands.callFor(command(LightOp.TURN_ON)).domain)
    }

    /**
     * Home Assistant has a real `light.toggle`, so this is **one call**. Hue has no such
     * request and has to read the state and send the inverse, which is why its toggle
     * can race somebody at the wall switch and this one cannot.
     */
    @Test
    fun `a toggle is one call rather than a read and a write`() {
        val call = HaCommands.callFor(command(LightOp.TOGGLE))

        assertEquals("toggle", call.service)
        assertTrue(call.data.contains("light.desk_lamp"))
    }

    @Test
    fun `a brightness is sent as a percentage`() {
        val call = HaCommands.callFor(command(LightOp.SET_BRIGHTNESS, brightness = 30))

        assertEquals("turn_on", call.service)
        assertTrue(call.data.contains("\"brightness_pct\":30"))
    }

    @Test
    fun `a colour is sent as three separate channels`() {
        val call = HaCommands.callFor(command(LightOp.SET_COLOUR, colour = 0xFFA757))

        assertTrue(call.data.contains("\"rgb_color\":[255,167,87]"))
    }

    /**
     * `kelvin` is a deprecated alias and `color_temp` is in **mireds** — so the wrong
     * name here is either silently ignored or read as a wildly different colour.
     */
    @Test
    fun `warmth is sent as color_temp_kelvin and not as kelvin or mireds`() {
        val call = HaCommands.callFor(command(LightOp.SET_TEMPERATURE, kelvin = 2_700))

        assertTrue(call.data.contains("\"color_temp_kelvin\":2700"))
        assertFalse(call.data.contains("\"kelvin\""))
        assertFalse(call.data.contains("color_temp\":"))
    }

    /**
     * The mapping most likely to be got wrong: an area addressed as an entity names
     * nothing, and Home Assistant reports no error for it.
     */
    @Test
    fun `an area target is addressed by area_id and a light by entity_id`() {
        val area = HaCommands.callFor(
            command(LightOp.TURN_ON, rid = "area:kitchen", kind = SmartHomeTargetKind.GROUP),
        )
        val light = HaCommands.callFor(command(LightOp.TURN_ON, rid = "light.desk_lamp"))

        assertTrue(area.data.contains("\"area_id\":\"kitchen\""))
        assertFalse(area.data.contains("entity_id"))
        assertTrue(light.data.contains("\"entity_id\":\"light.desk_lamp\""))
        assertFalse(light.data.contains("area_id"))
    }

    /**
     * The clearest single illustration of why the vendor seam is the whole facade: Hue
     * needs one paced write per lit light because a `grouped_light` write cannot say
     * "except that one", and here the same request is one call carrying a list.
     */
    @Test
    fun `only-lights-on narrows the target to a list in one call`() {
        val call = HaCommands.callFor(
            command(LightOp.SET_BRIGHTNESS, rid = "area:kitchen", kind = SmartHomeTargetKind.GROUP, onlyIfOn = true),
            targets = listOf("light.a", "light.b"),
        )

        assertTrue(call.data.contains("\"entity_id\":[\"light.a\",\"light.b\"]"))
        // The area must not also be sent: Home Assistant unions its targets, so a
        // request naming both would reach every light in the area after all.
        assertFalse(call.data.contains("area_id"))
    }

    @Test
    fun `activating a scene names the scene`() {
        val call = HaCommands.sceneCall(
            SceneRecall(hubId = "hub-1", rid = "scene.dinner", op = SceneOp.ACTIVATE),
            groupRid = "area:kitchen",
            sceneIsOn = false,
        )!!

        assertEquals("scene", call.domain)
        assertEquals("turn_on", call.service)
        assertTrue(call.data.contains("\"entity_id\":\"scene.dinner\""))
    }

    /**
     * **A scene has no "off" of its own** — it is a saved arrangement, and un-recalling
     * one is not a thing to ask for. What people mean is switching off the area it
     * belongs to, so that is what happens, and it is a `light.turn_off` rather than
     * anything in the scene domain.
     */
    @Test
    fun `turning a scene off switches off the area behind it`() {
        val call = HaCommands.sceneCall(
            SceneRecall(hubId = "hub-1", rid = "scene.dinner", op = SceneOp.TURN_OFF),
            groupRid = "area:kitchen",
            sceneIsOn = true,
        )!!

        assertEquals("light", call.domain)
        assertEquals("turn_off", call.service)
        assertTrue(call.data.contains("\"area_id\":\"kitchen\""))
    }

    @Test
    fun `a toggle recalls the scene when its area is dark and switches off when lit`() {
        fun toggle(lit: Boolean) = HaCommands.sceneCall(
            SceneRecall(hubId = "hub-1", rid = "scene.dinner", op = SceneOp.TOGGLE),
            groupRid = "area:kitchen",
            sceneIsOn = lit,
        )!!

        assertEquals("scene", toggle(lit = false).domain)
        assertEquals("turn_off", toggle(lit = true).service)
    }

    /**
     * A scene in no area cannot be switched off, and saying so beats sending a request
     * that would turn off every light in the house — which is what an empty `area_id`
     * would do.
     */
    @Test
    fun `a scene in no area answers nothing rather than an untargeted request`() {
        assertNull(
            HaCommands.sceneCall(
                SceneRecall(hubId = "hub-1", rid = "scene.dinner", op = SceneOp.TURN_OFF),
                groupRid = "",
                sceneIsOn = true,
            ),
        )
        // Activating one still works — it names the scene and needs no area at all.
        assertEquals(
            "scene",
            HaCommands.sceneCall(
                SceneRecall(hubId = "hub-1", rid = "scene.dinner", op = SceneOp.ACTIVATE),
                groupRid = "",
                sceneIsOn = false,
            )!!.domain,
        )
    }
}
