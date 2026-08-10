package com.example.ottomatic.data.hue

import com.example.ottomatic.core.service.LightCommand
import com.example.ottomatic.core.service.LightOp
import com.example.ottomatic.core.service.SmartHomeTargetKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Turns a [LightCommand] into the path and body the bridge expects.
 *
 * Pure, and separated from the transport for that reason: this is the half that can
 * be wrong in a way nothing reports — a bridge accepts a request it does not act on
 * and answers 200 — so it is the half worth testing exhaustively on the JVM.
 *
 * Two of the decisions here are the ones that quietly break:
 *
 * - **A brightness carries an `on` with it.** A bridge will happily store a dimming
 *   level on a light that is off, light nothing, and report success. "Set brightness
 *   to 30 %" means "have this light on, at 30 %" to everybody who has ever said it.
 * - **The fade is `dynamics.duration`.** The v1 API called it `transitiontime` and
 *   counted it in tenths of a second; sending that here is silently ignored, which
 *   reads as "the fade setting does nothing".
 */
internal object HueCommands {

    private val json = Json

    /** Where [kind] with this [rid] is written. */
    fun pathFor(kind: SmartHomeTargetKind, rid: String): String = when (kind) {
        SmartHomeTargetKind.LIGHT -> "$RESOURCE/light/$rid"
        SmartHomeTargetKind.GROUP -> "$RESOURCE/grouped_light/$rid"
        SmartHomeTargetKind.SCENE -> "$RESOURCE/scene/$rid"
    }

    /**
     * The body for [command].
     *
     * [LightOp.TOGGLE] never reaches here: it is not a request the bridge has, so
     * the caller reads the current state and sends the inverse as an ordinary
     * on-or-off.
     */
    fun bodyFor(command: LightCommand): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            when (command.op) {
                LightOp.TURN_ON, LightOp.TOGGLE -> putOn(true)
                LightOp.TURN_OFF -> putOn(false)
                LightOp.SET_BRIGHTNESS -> {
                    putSwitchOn(command)
                    putJsonObject("dimming") { put("brightness", command.brightnessPercent.toDouble()) }
                }
                LightOp.SET_COLOUR -> {
                    putSwitchOn(command)
                    val (x, y) = HueColour.xyFromRgb(command.colourRgb)
                    putJsonObject("color") { putJsonObject("xy") { put("x", x); put("y", y) } }
                }
                LightOp.SET_TEMPERATURE -> {
                    putSwitchOn(command)
                    putJsonObject("color_temperature") { put("mirek", HueColour.mirekFromKelvin(command.kelvin)) }
                }
            }
            putDuration(command.transitionMs)
        },
    )

    /** The body that recalls a scene. */
    fun sceneBody(transitionMs: Int): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            putJsonObject("recall") {
                put("action", "active")
                put("duration", transitionMs)
            }
        },
    )

    /**
     * The body that switches a light or group on or off, with nothing else in it.
     *
     * Its own entry point rather than a [LightCommand] built for the purpose,
     * because the scene node reaches for it without having a target of its own —
     * what it switches off is the group behind the scene, which it never asked the
     * user about.
     */
    fun onOffBody(on: Boolean, transitionMs: Int): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            putOn(on)
            putDuration(transitionMs)
        },
    )

    private fun kotlinx.serialization.json.JsonObjectBuilder.putOn(on: Boolean) {
        putJsonObject("on") { put("on", on) }
    }

    /**
     * The `on` that rides along with a value, unless the caller asked for the
     * opposite.
     *
     * [LightCommand.onlyIfOn] is the deliberate opt-out of the behaviour documented
     * at the top of this file: normally a brightness carries an `on` so that "set
     * it to 30 %" lights the lamp, and when the caller has already filtered down to
     * lights that *are* lit, sending it again would be claiming something about a
     * switch it was explicitly told not to touch.
     */
    private fun kotlinx.serialization.json.JsonObjectBuilder.putSwitchOn(command: LightCommand) {
        if (!command.onlyIfOn) putOn(true)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putDuration(transitionMs: Int) {
        // A zero duration is a snap, and saying so explicitly is not the same as
        // omitting the field — an omitted duration lets the bridge apply whatever
        // default the light was last given, which is why it is always written.
        putJsonObject("dynamics") { put("duration", transitionMs.coerceAtLeast(0)) }
    }

    private const val RESOURCE = "/clip/v2/resource"
}
