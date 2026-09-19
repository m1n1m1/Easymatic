package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.core.service.DndLevel
import io.github.m1n1m1.easymatic.core.service.OnOff
import io.github.m1n1m1.easymatic.domain.model.PlatformWarning
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.Hint
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.DndState
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.actionNode
import kotlinx.serialization.Serializable

/**
 * Config for `action.dnd`. [level] is only consulted when [state] is
 * [OnOff.ON]; [DndLevel.ALL] is a result-only value and so is not offered here.
 */
@Serializable
data class DndConfig(
    @Label("State") val state: OnOff = OnOff.ON,
    @Label("Level")
    @Hint("when on")
    val level: DndLevel = DndLevel.PRIORITY,
)

/**
 * Action for `action.dnd`. Toggles Do-Not-Disturb and reports the resulting
 * [DndState] on its `state` data port.
 *
 * Requires the `ACCESS_NOTIFICATION_POLICY` permission granted by the user;
 * when missing, [DndState.changed] is `false`.
 */
class DndAction : Action<DndConfig, DndState> {

    override val definition = actionNode<DndConfig, DndState>(
        typeId = "action.dnd",
        displayName = "Do Not Disturb",
        description = "Toggles Do-Not-Disturb on or off with a chosen policy level",
        platformWarnings = listOf(PlatformWarning.DND_GLOBAL_CONTROL),
        category = NodeCategory.DEVICE_SETTINGS,
        icon = NodeIcon.DND,
        permissions = listOf(DND_POLICY_PERMISSION),
        output = dataOut<DndState>("state"),
    )

    override suspend fun execute(input: DndConfig, context: ExecutionContext): NodeOutput<DndState> {
        val enabled = input.state.enabled
        val result = context.systemServices.setDnd(enabled, input.level)
        if (result?.changed != true) {
            context.log(
                "Do Not Disturb did not reach the requested state. Check policy access; " +
                    "on Android 15+, other active modes can keep Do Not Disturb enabled.",
                LogLevel.WARN,
            )
        }
        return NodeOutput(
            DndState(
                enabled = result?.enabled ?: enabled,
                level = result?.level ?: if (enabled) input.level else DndLevel.ALL,
                changed = result?.changed ?: false,
            ),
        )
    }
}
