package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.core.service.AudioDeviceType
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.items.SystemState
import io.github.m1n1m1.easymatic.engine.NodeOutput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/** The headset transitions `trigger.headset` can filter on. */
@Serializable
enum class HeadsetEvent {
    @Label("Connected")
    PLUGGED,
    @Label("Disconnected")
    UNPLUGGED,
}

@Serializable
data class AudioDeviceTriggerConfig(
    @Label("Event") val event: HeadsetEvent? = null,
    @Label("Device type") val deviceType: AudioDeviceType = AudioDeviceType.ANY,
)

/**
 * External audio connection changes. Retains the persisted headset node ID,
 * event choices and output values for compatibility with existing workflows.
 */
class HeadsetTrigger : Trigger<AudioDeviceTriggerConfig, SystemState> {

    override val definition = systemStateDefinition<AudioDeviceTriggerConfig>(
        typeId = "trigger.headset",
        displayName = "Audio Device Connected",
        description = "Starts when an external audio output of the selected type connects or disconnects. " +
            "Supports Bluetooth audio. Excludes built-in speakers and microphones.",
        category = NodeCategory.CONNECTIVITY,
        icon = NodeIcon.HEADSET,
    )

    override fun activate(
        config: AudioDeviceTriggerConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<SystemState>> = host.busEvents()
        .filter { it.source == TriggerSource.HARDWARE && it.payload[KEY_TRIGGER_TYPE] == "headset" }
        .filter { config.event == null || config.event.payloadValue == it.payload[KEY_EVENT] }
        .filter { bus ->
            val type = AudioDeviceType.entries.firstOrNull { it.name == bus.payload["deviceType"] }
            type != null && config.deviceType.matches(type)
        }
        .map { bus ->
            NodeOutput(
                SystemState(
                    event = bus.payload[KEY_EVENT].orEmpty(),
                    detail = bus.payload[KEY_DETAIL].orEmpty(),
                    timestamp = bus.timestamp,
                ),
            )
        }
}
