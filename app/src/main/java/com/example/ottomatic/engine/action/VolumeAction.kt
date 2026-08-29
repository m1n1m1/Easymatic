package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.AudioStream
import com.example.ottomatic.core.service.VolumeMode
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Hint
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.VolumeState
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode
import kotlinx.serialization.Serializable

/**
 * Config for `action.volume`. [value] (0..100) is only consulted when [mode] is
 * [VolumeMode.SET], and is scaled to the stream's maximum index.
 */
@Serializable
data class VolumeConfig(
    @Label("Stream") val stream: AudioStream = AudioStream.MEDIA,
    @Label("Mode") val mode: VolumeMode = VolumeMode.UP,
    @Label("Value")
    @Hint("0-100, only when mode is set")
    val value: Int = DEFAULT_VOLUME,
)

private const val DEFAULT_VOLUME = 50

/**
 * Action for `action.volume`. Adjusts an audio stream's volume — up, down, set
 * to an absolute index, mute or unmute — and reports the resulting
 * [VolumeState] on its `state` data port.
 */
class VolumeAction : Action<VolumeConfig, VolumeState> {

    override val definition = actionNode<VolumeConfig, VolumeState>(
        typeId = "action.volume",
        displayName = "Set Volume",
        description = "Adjusts an audio stream's volume (up, down, set, mute or unmute)",
        category = NodeCategory.DEVICE_SETTINGS,
        icon = NodeIcon.VOLUME,
        output = dataOut<VolumeState>("state"),
    )

    override suspend fun execute(input: VolumeConfig, context: ExecutionContext): NodeOutput<VolumeState> {
        val result = context.systemServices.setVolume(input.stream, input.mode, input.value)
        return NodeOutput(
            VolumeState(
                stream = result?.stream ?: input.stream,
                mode = result?.mode ?: input.mode,
                volume = result?.volume ?: 0,
                maxVolume = result?.maxVolume ?: 0,
                changed = result?.changed ?: false,
            ),
        )
    }
}
