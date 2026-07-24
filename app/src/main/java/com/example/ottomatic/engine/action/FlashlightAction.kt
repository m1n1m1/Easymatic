package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.TorchState
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode

/**
 * Action for `action.flashlight`. Toggles the camera torch (flashlight) on or
 * off and reports the resulting [TorchState] on its `state` data port.
 * Requires `CAMERA`; when no camera with a flash unit is available,
 * [TorchState.changed] is `false`.
 */
class FlashlightAction : Action<ToggleConfig, TorchState> {

    override val definition = actionNode<ToggleConfig, TorchState>(
        typeId = "action.flashlight",
        displayName = "Toggle Flashlight",
        description = "Turns the camera torch (flashlight) on or off",
        category = NodeCategory.DEVICE_SETTINGS,
        icon = NodeIcon.BOLT,
        output = dataOut<TorchState>("state"),
    )

    override suspend fun execute(input: ToggleConfig, context: ExecutionContext): NodeOutput<TorchState> {
        val result = context.systemServices.setTorch(input.enabled)
        return NodeOutput(TorchState(enabled = result?.enabled ?: input.enabled, changed = result?.changed ?: false))
    }
}
