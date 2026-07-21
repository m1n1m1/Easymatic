package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.items.VolumeState
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ActionInput
import com.example.ottomatic.engine.ActionResult
import com.example.ottomatic.engine.ExecutionContext

/**
 * Action for `action.volume`. Adjusts an audio stream's volume — up, down, set
 * to an absolute index, mute or unmute — and reports the resulting
 * [VolumeState] on its `state` data port.
 *
 * - `stream` (ENUM): `"media"`, `"ring"`, `"alarm"`, `"notification"`,
 *   `"system"` (default `"media"`).
 * - `mode` (ENUM): `"up"`, `"down"`, `"set"`, `"mute"`, `"unmute"`
 *   (default `"up"`).
 * - `value` (INT, 0..100): absolute index used only when `mode = "set"` —
 *   scaled to the stream's max volume (default `50`).
 */
class VolumeAction : Action {

    override val typeId: String = TYPE_ID

    override suspend fun execute(input: ActionInput, context: ExecutionContext): ActionResult {
        val stream = input.config.str("stream", default = "media")
        val mode = input.config.str("mode", default = "up")
        val value = input.config.int("value", default = 50)
        val result = context.systemServices.setVolume(stream, mode, value)
        val state = VolumeState(
            stream = result?.stream ?: stream,
            mode = result?.mode ?: mode,
            volume = result?.volume ?: 0,
            maxVolume = result?.maxVolume ?: 0,
            changed = result?.changed ?: false,
        )
        return ActionResult(
            execOut = listOf("out"),
            dataOut = mapOf("state" to Item.of(state)),
        )
    }

    companion object {
        const val TYPE_ID = "action.volume"
    }
}
