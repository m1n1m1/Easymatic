package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.ScreenTimeoutState
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.actionNode
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
        permissions = listOf(WRITE_SETTINGS_PERMISSION),
        output = dataOut<ScreenTimeoutState>("state"),
    )

    override suspend fun execute(
        input: ScreenTimeoutConfig,
        context: ExecutionContext,
    ): NodeOutput<ScreenTimeoutState> {
        val result = context.systemServices.setScreenTimeout(input.timeoutMs)
        if (result?.changed != true) {
            context.log(
                "ScreenTimeout: no device setting change was reported; check permissions and device restrictions",
                LogLevel.WARN,
            )
        }
        return NodeOutput(ScreenTimeoutState(ms = result?.ms ?: input.timeoutMs, changed = result?.changed ?: false))
    }
}
