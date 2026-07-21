package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.items.DndState
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ActionInput
import com.example.ottomatic.engine.ActionResult
import com.example.ottomatic.engine.ExecutionContext

/**
 * Action for `action.dnd`. Toggles the Do-Not-Disturb mode and reports the
 * resulting [DndState] on its `state` data port.
 *
 * - `state` (ENUM): `"on"` or `"off"` (default `"on"`).
 * - `level` (ENUM): `"priority"`, `"alarms"`, `"silence"` (default
 *   `"priority"`) — only used when `state = "on"`.
 *
 * Requires the `ACCESS_NOTIFICATION_POLICY` permission granted by the user;
 * when missing, [DndState.changed] is `false`.
 */
class DndAction : Action {

    override val typeId: String = TYPE_ID

    override suspend fun execute(input: ActionInput, context: ExecutionContext): ActionResult {
        val enabled = input.config.str("state", default = "on") != "off"
        val level = input.config.str("level", default = "priority")
        val result = context.systemServices.setDnd(enabled, level)
        val state = DndState(
            enabled = result?.enabled ?: enabled,
            level = result?.level ?: if (enabled) level else "all",
            changed = result?.changed ?: false,
        )
        return ActionResult(
            execOut = listOf("out"),
            dataOut = mapOf("state" to Item.of(state)),
        )
    }

    companion object {
        const val TYPE_ID = "action.dnd"
    }
}
