package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.VolumeState
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode

data class VolumeInput(val stream: String, val mode: String, val value: Int)

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
class VolumeAction : Action<VolumeInput, VolumeState> {

    override val definition = actionNode<VolumeInput, VolumeState>(
        typeId = "action.volume",
        displayName = "Set Volume",
        description = "Adjusts an audio stream's volume (up, down, set, mute or unmute)",
        category = NodeCategory.DEVICE_SETTINGS,
        iconKey = "volume",
        dataOutputs = listOf(dataOut<VolumeState>("state")),
        configFields = listOf(
            ConfigField(
                key = "stream",
                label = "Stream",
                type = ConfigFieldType.ENUM(
                    options = listOf("media", "ring", "alarm", "notification", "system"),
                ),
                defaultValue = "media",
            ),
            ConfigField(
                key = "mode",
                label = "Mode",
                type = ConfigFieldType.ENUM(options = listOf("up", "down", "set", "mute", "unmute")),
                defaultValue = "up",
            ),
            ConfigField(
                key = "value",
                label = "Value (0-100, only when mode = set)",
                type = ConfigFieldType.INT,
                defaultValue = "50",
            ),
        ),
        decode = { input ->
            VolumeInput(
                stream = input.configString("stream", "media"),
                mode = input.configString("mode", "up"),
                value = input.configInt("value", 50),
            )
        },
        encodeData = { state -> mapOf("state" to Item.of(state)) },
    )

    override suspend fun execute(input: VolumeInput, context: ExecutionContext): NodeOutput<VolumeState> {
        val result = context.systemServices.setVolume(input.stream, input.mode, input.value)
        val state = VolumeState(
            stream = result?.stream ?: input.stream,
            mode = result?.mode ?: input.mode,
            volume = result?.volume ?: 0,
            maxVolume = result?.maxVolume ?: 0,
            changed = result?.changed ?: false,
        )
        return NodeOutput(state)
    }
}
