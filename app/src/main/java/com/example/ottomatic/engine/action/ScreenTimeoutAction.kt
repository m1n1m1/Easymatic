package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.ScreenTimeoutState
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode
import kotlinx.serialization.Serializable

/** Config for `action.screen_timeout`. */
@Serializable
data class ScreenTimeoutConfig(
    @Label("Timeout (ms)") val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
)

private const val DEFAULT_TIMEOUT_MS = 30_000

/**
 * Action for `action.screen_timeout`. Sets the screen-off timeout in
 * milliseconds and reports the resulting [ScreenTimeoutState] on its `state`
 * data port. Requires `WRITE_SETTINGS`.
 */
class ScreenTimeoutAction : Action<ScreenTimeoutConfig, ScreenTimeoutState> {

    override val definition = actionNode<ScreenTimeoutConfig, ScreenTimeoutState>(
        typeId = "action.screen_timeout",
        displayName = "Set Screen Timeout",
        description = "Sets the screen-off timeout in milliseconds",
        category = NodeCategory.DEVICE_SETTINGS,
        icon = NodeIcon.TIMER,
        output = dataOut<ScreenTimeoutState>("state"),
    )

    override suspend fun execute(
        input: ScreenTimeoutConfig,
        context: ExecutionContext,
    ): NodeOutput<ScreenTimeoutState> {
        val result = context.systemServices.setScreenTimeout(input.timeoutMs)
        return NodeOutput(ScreenTimeoutState(ms = result?.ms ?: input.timeoutMs, changed = result?.changed ?: false))
    }
}
