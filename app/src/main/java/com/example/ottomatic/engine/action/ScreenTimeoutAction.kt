package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.items.ScreenTimeoutState
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ActionInput
import com.example.ottomatic.engine.ActionResult
import com.example.ottomatic.engine.ExecutionContext

/**
 * Action for `action.screen_timeout`. Sets the screen-off timeout in
 * milliseconds and reports the resulting [ScreenTimeoutState] on its `state`
 * data port. Requires `WRITE_SETTINGS`.
 */
class ScreenTimeoutAction : Action {

    override val typeId: String = TYPE_ID

    override suspend fun execute(input: ActionInput, context: ExecutionContext): ActionResult {
        val ms = input.config.int("ms", default = 30_000)
        val result = context.systemServices.setScreenTimeout(ms)
        val state = ScreenTimeoutState(ms = result?.ms ?: ms, changed = result?.changed ?: false)
        return ActionResult(execOut = listOf("out"), dataOut = mapOf("state" to Item.of(state)))
    }

    companion object {
        const val TYPE_ID = "action.screen_timeout"
    }
}
