package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.TorchState
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode

data class FlashlightInput(val enabled: Boolean)

/**
 * Action for `action.flashlight`. Toggles the camera torch (flashlight) on or
 * off and reports the resulting [TorchState] on its `state` data port.
 * Requires `CAMERA`; when no camera with a flash unit is available,
 * [TorchState.changed] is `false`.
 */
class FlashlightAction : Action<FlashlightInput, TorchState> {

    override val definition = actionNode<FlashlightInput, TorchState>(
        typeId = "action.flashlight",
        displayName = "Toggle Flashlight",
        description = "Turns the camera torch (flashlight) on or off",
        category = NodeCategory.DEVICE_SETTINGS,
        iconKey = "bolt",
        dataOutputs = listOf(dataOut<TorchState>("state")),
        configFields = listOf(
            ConfigField(
                key = "state",
                label = "State",
                type = ConfigFieldType.ENUM(options = listOf("on", "off")),
                defaultValue = "on",
            ),
        ),
        decode = { input -> FlashlightInput(input.configString("state", "on") != "off") },
        encodeData = { state -> mapOf("state" to Item.of(state)) },
    )

    override suspend fun execute(input: FlashlightInput, context: ExecutionContext): NodeOutput<TorchState> {
        val result = context.systemServices.setTorch(input.enabled)
        return NodeOutput(TorchState(enabled = result?.enabled ?: input.enabled, changed = result?.changed ?: false))
    }
}
