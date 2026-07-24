package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.AutoRotateState
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode

/**
 * Action for `action.auto_rotate`. Toggles accelerometer-based auto-rotation
 * on or off and reports the resulting [AutoRotateState] on its `state` data
 * port. Requires `WRITE_SETTINGS`.
 */
class AutoRotateAction : Action<ToggleConfig, AutoRotateState> {

    override val definition = actionNode<ToggleConfig, AutoRotateState>(
        typeId = "action.auto_rotate",
        displayName = "Toggle Auto-Rotate",
        description = "Turns accelerometer auto-rotation on or off",
        category = NodeCategory.DEVICE_SETTINGS,
        icon = NodeIcon.BOLT,
        output = dataOut<AutoRotateState>("state"),
    )

    override suspend fun execute(input: ToggleConfig, context: ExecutionContext): NodeOutput<AutoRotateState> {
        val result = context.systemServices.setAutoRotate(input.enabled)
        return NodeOutput(
            AutoRotateState(
                enabled = result?.enabled ?: input.enabled,
                changed = result?.changed ?: false,
            ),
        )
    }
}
