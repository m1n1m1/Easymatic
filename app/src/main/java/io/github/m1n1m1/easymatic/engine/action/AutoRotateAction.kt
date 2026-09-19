package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.AutoRotateState
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.actionNode

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
        permissions = listOf(WRITE_SETTINGS_PERMISSION),
        output = dataOut<AutoRotateState>("state"),
    )

    override suspend fun execute(input: ToggleConfig, context: ExecutionContext): NodeOutput<AutoRotateState> {
        val result = context.systemServices.setAutoRotate(input.enabled)
        if (result?.changed != true) {
            context.log(
                "AutoRotate: no device setting change was reported; check permissions and device restrictions",
                LogLevel.WARN,
            )
        }
        return NodeOutput(
            AutoRotateState(
                enabled = result?.enabled ?: input.enabled,
                changed = result?.changed ?: false,
            ),
        )
    }
}
