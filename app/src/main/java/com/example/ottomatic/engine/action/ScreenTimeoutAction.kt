package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.ScreenTimeoutState
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode

data class ScreenTimeoutInput(val milliseconds: Int)

/**
 * Action for `action.screen_timeout`. Sets the screen-off timeout in
 * milliseconds and reports the resulting [ScreenTimeoutState] on its `state`
 * data port. Requires `WRITE_SETTINGS`.
 */
class ScreenTimeoutAction : Action<ScreenTimeoutInput, ScreenTimeoutState> {

    override val definition = actionNode<ScreenTimeoutInput, ScreenTimeoutState>(
        typeId = "action.screen_timeout",
        displayName = "Set Screen Timeout",
        description = "Sets the screen-off timeout in milliseconds",
        category = NodeCategory.DEVICE_SETTINGS,
        iconKey = "timer",
        dataOutputs = listOf(dataOut<ScreenTimeoutState>("state")),
        configFields = listOf(
            ConfigField(
                key = "ms",
                label = "Timeout (ms)",
                type = ConfigFieldType.INT,
                defaultValue = "30000",
            ),
        ),
        decode = { input -> ScreenTimeoutInput(input.configInt("ms", 30_000)) },
        encodeData = { state -> mapOf("state" to Item.of(state)) },
    )

    override suspend fun execute(input: ScreenTimeoutInput, context: ExecutionContext): NodeOutput<ScreenTimeoutState> {
        val result = context.systemServices.setScreenTimeout(input.milliseconds)
        return NodeOutput(ScreenTimeoutState(ms = result?.ms ?: input.milliseconds, changed = result?.changed ?: false))
    }
}
