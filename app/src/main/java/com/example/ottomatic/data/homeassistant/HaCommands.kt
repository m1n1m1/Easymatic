package com.example.ottomatic.data.homeassistant

import com.example.ottomatic.core.service.LightCommand
import com.example.ottomatic.core.service.LightOp
import com.example.ottomatic.core.service.SceneOp
import com.example.ottomatic.core.service.SceneRecall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Turns a [LightCommand] or [SceneRecall] into the service call Home Assistant expects.
 *
 * Pure, and separated from the transport for `HueCommands`' reason: this is the half
 * that can be wrong in a way **nothing reports**. Home Assistant answers
 * `{"success": true}` to a service call it carried out against nothing at all — a
 * misspelled attribute is simply dropped, an entity id that names nothing is simply not
 * found — so every mistake here produces a node that reports success and changes
 * nothing, and this is the half worth testing exhaustively on the JVM.
 *
 * Three decisions are the ones that quietly break, and each has a test named after it:
 *
 * - **`transition` is in seconds.** The facade speaks milliseconds, as every other
 *   duration in this app does, and Home Assistant counts this one in seconds. Passing
 *   the number through unconverted turns the 400 ms default into a **six and a half
 *   minute** fade, which does not read as a unit bug — it reads as the light never
 *   having changed. This is Home Assistant's mireds.
 * - **The temperature attribute is `color_temp_kelvin`.** There is an older `kelvin`
 *   alias and a `color_temp` that is in *mireds*, so the wrong name here is either
 *   ignored or interpreted as a wildly different colour.
 * - **`light.turn_on` with a value also switches the light on**, which is what the app
 *   documents and wants. Unlike Hue, nothing extra has to be sent to get it — and
 *   unlike Hue, "leave the switched-off ones alone" is expressed by *narrowing the
 *   target* rather than by omitting a field.
 */
internal object HaCommands {

    private val json = Json

    /** A service to call, and what to send with it. */
    data class ServiceCall(val domain: String, val service: String, val data: String)

    /**
     * The call for [command], aimed at [targets].
     *
     * [targets] is the list of entity ids to act on, or empty to act on the command's
     * own target — which is either one entity or, for a group, the whole area. Passing
     * an explicit list is how `onlyIfOn` narrows a room to the lamps that are lit: on
     * this vendor that is **one call carrying a list**, where Hue needs one write per
     * light, and it is the clearest single illustration of why the vendor seam is the
     * whole facade rather than a shared transport.
     */
    fun callFor(command: LightCommand, targets: List<String> = emptyList()): ServiceCall {
        val service = when (command.op) {
            LightOp.TURN_OFF -> TURN_OFF
            // Home Assistant has a real toggle, so this is one call. Hue has to read
            // the state and send the inverse, which is what makes its toggle the one
            // operation that can race somebody at the wall switch.
            LightOp.TOGGLE -> TOGGLE
            else -> TURN_ON
        }
        val data = buildJsonObject {
            putTarget(command, targets)
            putTransition(command.transitionMs)
            when (command.op) {
                LightOp.SET_BRIGHTNESS -> put(BRIGHTNESS_PCT, command.brightnessPercent)
                LightOp.SET_COLOUR -> putJsonArray(RGB_COLOUR) {
                    val rgb = command.colourRgb
                    add((rgb shr RED_SHIFT) and BYTE)
                    add((rgb shr GREEN_SHIFT) and BYTE)
                    add(rgb and BYTE)
                }
                LightOp.SET_TEMPERATURE -> put(COLOUR_TEMP_KELVIN, command.kelvin)
                LightOp.TURN_ON, LightOp.TURN_OFF, LightOp.TOGGLE -> Unit
            }
        }
        return ServiceCall(LIGHT_DOMAIN, service, json.encodeToString(JsonObject.serializer(), data))
    }

    /**
     * The call for [request].
     *
     * **A scene has no "off" of its own**, exactly as on Hue: recalling a saved
     * arrangement is a real operation and un-recalling it is not. So turning one off is
     * `light.turn_off` aimed at the *area behind the scene*, which the snapshot already
     * knows — and unlike Hue, it always does, because the area template resolved it
     * when the snapshot was taken.
     *
     * [groupRid] is the area behind the scene, or blank when it is in none.
     */
    fun sceneCall(request: SceneRecall, groupRid: String, sceneIsOn: Boolean): ServiceCall? {
        val activate = ServiceCall(
            SCENE_DOMAIN,
            TURN_ON,
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    putEntity(request.rid)
                    putTransition(request.transitionMs)
                },
            ),
        )
        return when {
            request.op == SceneOp.ACTIVATE -> activate
            // A toggle with the area already lit means the scene is showing, so switch
            // the area off; otherwise recall it.
            request.op == SceneOp.TOGGLE && !sceneIsOn -> activate
            groupRid.isBlank() -> null
            else -> ServiceCall(
                LIGHT_DOMAIN,
                TURN_OFF,
                json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        putAreaOrEntity(groupRid)
                        putTransition(request.transitionMs)
                    },
                ),
            )
        }
    }

    /**
     * Home Assistant counts a fade in **seconds**, where the facade speaks
     * milliseconds.
     *
     * Sent as a fraction rather than rounded to whole seconds, so the 400 ms default
     * stays the 0.4 s snap it is meant to be rather than becoming either an instant one
     * or a full second.
     */
    private fun JsonObjectBuilder.putTransition(transitionMs: Int) {
        put(TRANSITION, transitionMs / MS_PER_SECOND)
    }

    private fun JsonObjectBuilder.putTarget(command: LightCommand, targets: List<String>) {
        when {
            targets.isNotEmpty() -> putJsonArray(ENTITY_ID) { targets.forEach { add(it) } }
            else -> putAreaOrEntity(command.rid)
        }
    }

    private fun JsonObjectBuilder.putAreaOrEntity(rid: String) {
        val areaId = HaIds.areaIdOf(rid)
        if (areaId != null) put(AREA_ID, areaId) else putEntity(rid)
    }

    private fun JsonObjectBuilder.putEntity(entityId: String) {
        put(ENTITY_ID, entityId)
    }

    private const val LIGHT_DOMAIN = "light"
    private const val SCENE_DOMAIN = "scene"
    private const val TURN_ON = "turn_on"
    private const val TURN_OFF = "turn_off"
    private const val TOGGLE = "toggle"
    private const val ENTITY_ID = "entity_id"
    private const val AREA_ID = "area_id"
    private const val TRANSITION = "transition"
    private const val BRIGHTNESS_PCT = "brightness_pct"
    private const val RGB_COLOUR = "rgb_color"

    /** The current name. `kelvin` is a deprecated alias and `color_temp` is in mireds. */
    private const val COLOUR_TEMP_KELVIN = "color_temp_kelvin"

    private const val MS_PER_SECOND = 1000.0
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val BYTE = 0xFF
}
