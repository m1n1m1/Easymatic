package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.AutoRotateState
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode

data class AutoRotateInput(val enabled: Boolean)

/**
 * Action for `action.auto_rotate`. Toggles accelerometer-based auto-rotation
 * on or off and reports the resulting [AutoRotateState] on its `state` data
 * port. Requires `WRITE_SETTINGS`.
 */
class AutoRotateAction : Action<AutoRotateInput, AutoRotateState> {

    override val definition = actionNode<AutoRotateInput, AutoRotateState>(
        typeId = "action.auto_rotate",
        displayName = "Toggle Auto-Rotate",
        description = "Turns accelerometer auto-rotation on or off",
        category = NodeCategory.DEVICE_SETTINGS,
        iconKey = "bolt",
        dataOutputs = listOf(dataOut<AutoRotateState>("state")),
        configFields = listOf(
            ConfigField(
                key = "state",
                label = "State",
                type = ConfigFieldType.ENUM(options = listOf("on", "off")),
                defaultValue = "on",
            ),
        ),
        decode = { input -> AutoRotateInput(input.configString("state", "on") != "off") },
        encodeData = { state -> mapOf("state" to Item.of(state)) },
    )

    override suspend fun execute(input: AutoRotateInput, context: ExecutionContext): NodeOutput<AutoRotateState> {
        val result = context.systemServices.setAutoRotate(input.enabled)
        return NodeOutput(
            AutoRotateState(
                enabled = result?.enabled ?: input.enabled,
                changed = result?.changed ?: false,
            ),
        )
    }
}
