package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.DndLevel
import com.example.ottomatic.core.service.OnOff
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Hint
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.DndState
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode
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
        category = NodeCategory.DEVICE_SETTINGS,
        icon = NodeIcon.DND,
        output = dataOut<DndState>("state"),
    )

    override suspend fun execute(input: DndConfig, context: ExecutionContext): NodeOutput<DndState> {
        val enabled = input.state.enabled
        val result = context.systemServices.setDnd(enabled, input.level)
        return NodeOutput(
            DndState(
                enabled = result?.enabled ?: enabled,
                level = result?.level ?: if (enabled) input.level else DndLevel.ALL,
                changed = result?.changed ?: false,
            ),
        )
    }
}
