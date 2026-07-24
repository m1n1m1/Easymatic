package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.items.CallInitiated
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ActionInput
import com.example.ottomatic.engine.ActionResult
import com.example.ottomatic.engine.ExecutionContext

/**
 * Action for `action.call`. Initiates a phone call to `number` via
 * `ACTION_CALL` (requires `CALL_PHONE`). `number` may be wired from upstream
 * data or set as a static literal. Reports [CallInitiated] on its `state`
 * data port.
 */
class CallAction : Action {

    override val typeId: String = TYPE_ID

    override suspend fun execute(input: ActionInput, context: ExecutionContext): ActionResult {
        val number = input.string("number", default = "")
        val ok = context.systemServices.call(number)
        val state = CallInitiated(number = number, initiated = ok)
        return ActionResult(execOut = listOf("out"), dataOut = mapOf("state" to Item.of(state)))
    }

    companion object {
        const val TYPE_ID = "action.call"
    }
}
