package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.core.service.OnOffToggle
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.TorchState
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.actionNode
import kotlinx.serialization.Serializable

/**
 * Config for `action.flashlight`.
 *
 * Its own class rather than the shared [ToggleConfig], because this is the one
 * device toggle whose state can be read back and therefore the one that can offer
 * [OnOffToggle.TOGGLE]. Sharing the config would have meant putting a third option
 * on `action.wifi` and `action.bluetooth` that neither of them can honour.
 */
@Serializable
data class FlashlightConfig(
    @Label("State") val state: OnOffToggle = OnOffToggle.ON,
)

/**
 * Action for `action.flashlight`. Turns the camera torch (flashlight) on, off or
 * to the opposite of what it is now, and reports the resulting [TorchState] on its
 * `state` data port. Requires `CAMERA`; when no camera with a flash unit is
 * available, [TorchState.changed] is `false`.
 *
 * **Toggle needs a reading, and a reading can be missing.** The torch state comes
 * from `DeviceState.isTorchOn`, which answers null while the platform has not said
 * — no flash unit, or another app holding the camera. The node then touches the
 * torch **not at all** and reports `changed = false` rather than guessing a
 * direction, because a guess would be a coin flip that looks like a working macro.
 */
class FlashlightAction : Action<FlashlightConfig, TorchState> {

    override val definition = actionNode<FlashlightConfig, TorchState>(
        typeId = "action.flashlight",
        displayName = "Toggle Flashlight",
        description = "Turns the camera torch (flashlight) on, off or to the opposite of its current state",
        category = NodeCategory.DEVICE_SETTINGS,
        icon = NodeIcon.BOLT,
        permissions = listOf(TORCH_CAMERA_PERMISSION),
        output = dataOut<TorchState>("state"),
    )

    override suspend fun execute(input: FlashlightConfig, context: ExecutionContext): NodeOutput<TorchState> {
        val wanted = input.state.resolve(context.deviceState.isTorchOn())
        if (wanted == null) {
            context.log("Cannot toggle the flashlight: its current state is unknown", LogLevel.WARN)
            return NodeOutput(TorchState(enabled = false, changed = false))
        }
        val result = context.systemServices.setTorch(wanted)
        if (result?.changed != true) {
            context.log(
                "Flashlight: no device setting change was reported; check permissions and device restrictions",
                LogLevel.WARN,
            )
        }
        return NodeOutput(TorchState(enabled = result?.enabled ?: wanted, changed = result?.changed ?: false))
    }
}
