package io.github.m1n1m1.easymatic.engine.value

import io.github.m1n1m1.easymatic.core.service.AudioDeviceType
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.ValueNode
import io.github.m1n1m1.easymatic.engine.valueNode
import kotlinx.serialization.Serializable

@Serializable
data class AudioDeviceConfig(
    @Label("Device type") val deviceType: AudioDeviceType = AudioDeviceType.ANY,
)

/** Retains the old node and port identifiers so saved workflows keep their wiring. */
class AudioDeviceConnectedValue : ValueNode<AudioDeviceConfig, Boolean> {
    override val definition = valueNode<AudioDeviceConfig, Boolean>(
        typeId = "value.headset",
        displayName = "Audio Device Connected",
        description = "Whether an external audio output of the selected type is connected. " +
            "Supports Bluetooth audio. Excludes built-in speakers and microphones.",
        category = NodeCategory.VALUE_CONNECTIVITY,
        icon = NodeIcon.HEADSET,
        output = dataOut("plugged", label = "Connected"),
    )

    override suspend fun read(config: AudioDeviceConfig, context: ExecutionContext): Boolean? =
        context.deviceState.isAudioDeviceConnected(config.deviceType)
}
