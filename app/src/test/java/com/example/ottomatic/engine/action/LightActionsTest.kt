package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.LightCommand
import com.example.ottomatic.core.service.LightOp
import com.example.ottomatic.core.service.LightReading
import com.example.ottomatic.core.service.SceneOp
import com.example.ottomatic.core.service.SmartHomeLimits
import com.example.ottomatic.core.service.SmartHomeTargetKind
import com.example.ottomatic.domain.model.SmartHomeRef
import com.example.ottomatic.engine.DefaultExecutionContext
import com.example.ottomatic.engine.FakeSmartHome
import com.example.ottomatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three light nodes.
 *
 * Most of these are about what happens when something is wrong, because that is the
 * contract these nodes actually have: **report it, put it on the data port, and
 * pulse `out` anyway**. A node that threw would stop the macro halfway, and a light
 * that did not come on is not a reason to abandon everything after it.
 *
 * The other recurring assertion is that nothing reached the facade. A malformed
 * reference must be refused *before* the round trip, not after — otherwise a mistake
 * in a config field is indistinguishable from a bridge that is switched off.
 */
class LightActionsTest {

    private val hub = FakeSmartHome()
    private val context = DefaultExecutionContext(
        systemServices = RecordingSystemServices(),
        smartHome = hub,
    )

    private val control = LightControlAction()
    private val scene = LightSceneAction()
    private val state = LightStateAction()

    private fun ref(kind: SmartHomeTargetKind, name: String = "Desk lamp") =
        SmartHomeRef.format("hub-1", kind, "rid-9", name)

    @Test
    fun `an unconfigured target never reaches the hub`() = runBlocking {
        val out = control.execute(LightControlConfig(target = ""), context)

        assertFalse(out.value.changed)
        assertEquals("No light chosen", out.value.error)
        assertTrue(hub.applied.isEmpty())
    }

    /**
     * Names the string it read rather than only that it failed: a spec that arrived
     * through a variable or a text transform is exactly the case where seeing it is
     * what explains the failure.
     */
    @Test
    fun `a malformed reference is reported with what was read`() = runBlocking {
        val out = control.execute(LightControlConfig(target = "kitchen"), context)

        assertEquals("Not a light reference: \"kitchen\"", out.value.error)
        assertTrue(hub.applied.isEmpty())
    }

    /**
     * The two pickers write the same kind of spec, so one can end up in the other's
     * field through a wire. Saying which node it belongs in beats a bridge answering
     * 404.
     */
    @Test
    fun `a scene in the light node and a light in the scene node are both refused`() = runBlocking {
        val wrongWay = control.execute(LightControlConfig(target = ref(SmartHomeTargetKind.SCENE)), context)
        assertEquals("That is a scene, not a light — use Activate Scene", wrongWay.value.error)

        val otherWay = scene.execute(LightSceneConfig(scene = ref(SmartHomeTargetKind.LIGHT)), context)
        assertEquals("That is a light, not a scene — use Control Light", otherWay.value.error)

        assertTrue(hub.applied.isEmpty())
        assertTrue(hub.recalled.isEmpty())
    }

    /** Refused before the round trip, and by name — sending black would turn a light on wrong. */
    @Test
    fun `an uninterpretable colour is refused before anything is sent`() = runBlocking {
        val out = control.execute(
            LightControlConfig(target = ref(SmartHomeTargetKind.LIGHT), op = LightOp.SET_COLOUR, colour = "blu"),
            context,
        )

        assertEquals("Not a colour: \"blu\"", out.value.error)
        assertTrue(hub.applied.isEmpty())
    }

    /**
     * The colour field is only read for the colour operation, so a stale or empty
     * one left behind by switching the dropdown must not block turning a light on.
     */
    @Test
    fun `a colour that is not being used is not validated`() = runBlocking {
        val out = control.execute(
            LightControlConfig(target = ref(SmartHomeTargetKind.LIGHT), op = LightOp.TURN_ON, colour = "blu"),
            context,
        )

        assertTrue(out.value.changed)
        assertEquals(1, hub.applied.size)
    }

    @Test
    fun `a good reference reaches the hub whole`() = runBlocking {
        control.execute(
            LightControlConfig(
                target = ref(SmartHomeTargetKind.GROUP, "Kitchen"),
                op = LightOp.SET_BRIGHTNESS,
                brightness = 30,
            ),
            context,
        )

        val command = hub.applied.single()
        assertEquals("hub-1", command.hubId)
        assertEquals(SmartHomeTargetKind.GROUP, command.kind)
        assertEquals("rid-9", command.rid)
        assertEquals(30, command.brightnessPercent)
    }

    @Test
    fun `brightness and fade are clamped to what means anything`() = runBlocking {
        control.execute(
            LightControlConfig(
                target = ref(SmartHomeTargetKind.LIGHT),
                op = LightOp.SET_BRIGHTNESS,
                brightness = 500,
                transitionMs = 10_000_000,
            ),
            context,
        )

        val command = hub.applied.single()
        assertEquals(100, command.brightnessPercent)
        assertEquals(SmartHomeLimits.MAX_TRANSITION_MS, command.transitionMs)
    }

    /**
     * The toggle is carried to the facade rather than acted on here, because what
     * it means — read the group, write only to the lit members — is the bridge's
     * shape and belongs in `data/hue/`.
     */
    @Test
    fun `only-lights-on is passed through for the operations that can honour it`() = runBlocking {
        control.execute(
            LightControlConfig(
                target = ref(SmartHomeTargetKind.GROUP, "Living room"),
                op = LightOp.SET_TEMPERATURE,
                kelvin = 2200,
                onlyLightsOn = true,
            ),
            context,
        )

        assertTrue(hub.applied.single().onlyIfOn)
    }

    /**
     * `changed = false` with a **blank** error is the one answer that is not a
     * failure: the hub was reached and had nothing lit to change. It must not read
     * as an error to an `action.if`, and `out` still pulses.
     */
    @Test
    fun `nothing being on is reported as no change rather than as a failure`() = runBlocking {
        hub.applyFailure = null
        hub.changed = false

        val out = control.execute(
            LightControlConfig(
                target = ref(SmartHomeTargetKind.GROUP, "Living room"),
                op = LightOp.SET_BRIGHTNESS,
                brightness = 20,
                onlyLightsOn = true,
            ),
            context,
        )

        assertFalse(out.value.changed)
        assertEquals("", out.value.error)
    }

    /** A bridge that is unplugged lands on the data port; the macro keeps going. */
    @Test
    fun `a hub failure lands on the port rather than stopping the run`() = runBlocking {
        hub.applyFailure = "The bridge at 192.168.1.42 could not be reached"

        val out = control.execute(LightControlConfig(target = ref(SmartHomeTargetKind.LIGHT)), context)

        assertFalse(out.value.changed)
        assertEquals("The bridge at 192.168.1.42 could not be reached", out.value.error)
        assertEquals("TURN_ON", out.value.op)
    }

    @Test
    fun `a scene is recalled with its own hub and rid`() = runBlocking {
        val out = scene.execute(LightSceneConfig(scene = ref(SmartHomeTargetKind.SCENE, "Dinner")), context)

        assertTrue(out.value.changed)
        assertEquals("hub-1", hub.recalled.single().hubId)
        assertEquals("rid-9", hub.recalled.single().rid)
        assertEquals(SceneOp.ACTIVATE, hub.recalled.single().op)
    }

    /**
     * The three operations reach the facade unchanged: which room a turn-off acts
     * on is worked out from the scene down in `data/`, so nothing here has to ask
     * for a second target.
     */
    @Test
    fun `each scene operation is carried through as chosen`() = runBlocking {
        SceneOp.entries.forEach { op ->
            scene.execute(LightSceneConfig(scene = ref(SmartHomeTargetKind.SCENE), op = op), context)
        }

        assertEquals(SceneOp.entries.toList(), hub.recalled.map { it.op })
    }

    /**
     * "Activated" would be a lie for two of the three operations, and a `false`
     * after a successful turn-off would read to an `action.if` as a failure.
     */
    @Test
    fun `a scene turned off reports that it changed, not that it activated`() = runBlocking {
        val out = scene.execute(
            LightSceneConfig(scene = ref(SmartHomeTargetKind.SCENE), op = SceneOp.TURN_OFF),
            context,
        )

        assertTrue(out.value.changed)
        assertEquals("TURN_OFF", out.value.op)
    }

    @Test
    fun `a scene node failure lands on the port rather than stopping the run`() = runBlocking {
        hub.recallFailure = "The bridge could not be reached"

        val out = scene.execute(
            LightSceneConfig(scene = ref(SmartHomeTargetKind.SCENE), op = SceneOp.TOGGLE),
            context,
        )

        assertFalse(out.value.changed)
        assertEquals("TOGGLE", out.value.op)
        assertEquals("The bridge could not be reached", out.value.error)
    }

    @Test
    fun `reading a light answers a struct with its colour as text`() = runBlocking {
        hub.reading = LightReading(
            found = true,
            name = "Ceiling",
            on = true,
            brightnessPercent = 42.5,
            colourRgb = 0xFF8800,
            kelvin = 2700,
            reachable = true,
        )

        val out = state.execute(LightStateConfig(target = ref(SmartHomeTargetKind.LIGHT)), context)

        assertTrue(out.value.found)
        assertEquals("Ceiling", out.value.name)
        assertEquals(42.5, out.value.brightness, 0.001)
        // Hex text, so it renders into a notification unchanged and wires straight
        // back into Control Light's Colour field.
        assertEquals("#FF8800", out.value.colour)
        assertTrue(out.value.reachable)
    }

    /**
     * The pair that stops "the light is off" and "the bridge was unreachable"
     * looking the same to an `action.if`. Without [LightState.found] there would be
     * one answer for both.
     */
    @Test
    fun `an unreachable bridge is not the same answer as a light that is off`() = runBlocking {
        hub.readFailure = "The bridge could not be reached"

        val out = state.execute(LightStateConfig(target = ref(SmartHomeTargetKind.LIGHT)), context)

        assertFalse(out.value.found)
        assertFalse(out.value.on)
        assertEquals("The bridge could not be reached", out.value.error)
    }

    /** A bulb with no colour gamut has no colour — which is not the same as black. */
    @Test
    fun `a light with no gamut answers a blank colour rather than black`() = runBlocking {
        hub.reading = LightReading(found = true, name = "Lamp", on = true, colourRgb = LightCommand.NO_COLOUR)

        val out = state.execute(LightStateConfig(target = ref(SmartHomeTargetKind.LIGHT)), context)

        assertEquals("", out.value.colour)
    }

    @Test
    fun `a scene has no state to read`() = runBlocking {
        val out = state.execute(LightStateConfig(target = ref(SmartHomeTargetKind.SCENE)), context)

        assertFalse(out.value.found)
        assertTrue(hub.read.isEmpty())
    }

    /**
     * The default facade, which is what an engine-only test and a phone with no hub
     * both see. It must be the same answer the nodes already handle.
     */
    @Test
    fun `with no hub set up at all the node still reports and carries on`() = runBlocking {
        val bare = DefaultExecutionContext(systemServices = RecordingSystemServices())

        val out = control.execute(LightControlConfig(target = ref(SmartHomeTargetKind.LIGHT)), bare)

        assertFalse(out.value.changed)
        assertEquals("No smart-home hub is set up on this phone", out.value.error)
    }
}
