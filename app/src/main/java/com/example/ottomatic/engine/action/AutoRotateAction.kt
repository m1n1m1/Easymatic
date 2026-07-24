package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.items.AutoRotateState
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ActionInput
import com.example.ottomatic.engine.ActionResult
import com.example.ottomatic.engine.ExecutionContext

/**
 * Action for `action.auto_rotate`. Toggles accelerometer-based auto-rotation
 * on or off and reports the resulting [AutoRotateState] on its `state` data
 * port. Requires `WRITE_SETTINGS`.
 */
class AutoRotateAction : Action {

    override val typeId: String = TYPE_ID

    override suspend fun execute(input: ActionInput, context: ExecutionContext): ActionResult {
        val enabled = input.config.str("state", default = "on") != "off"
        val result = context.systemServices.setAutoRotate(enabled)
        val state = AutoRotateState(enabled = result?.enabled ?: enabled, changed = result?.changed ?: false)
        return ActionResult(execOut = listOf("out"), dataOut = mapOf("state" to Item.of(state)))
    }

    companion object {
        const val TYPE_ID = "action.auto_rotate"
    }
}
