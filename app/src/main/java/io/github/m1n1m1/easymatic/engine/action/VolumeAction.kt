package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.core.service.AudioStream
import io.github.m1n1m1.easymatic.core.service.VolumeMode
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.Hint
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.VolumeState
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.actionNode
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
        permissions = listOf(DND_POLICY_PERMISSION),
        output = dataOut<VolumeState>("state"),
    )

    override suspend fun execute(input: VolumeConfig, context: ExecutionContext): NodeOutput<VolumeState> {
        val result = context.systemServices.setVolume(input.stream, input.mode, input.value)
        if (result?.changed != true) {
            context.log(
                "Volume: no device setting change was reported; check permissions and device restrictions",
                LogLevel.WARN,
            )
        }
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
