package com.example.ottomatic.engine.action

import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ActionInput
import com.example.ottomatic.engine.ActionResult
import com.example.ottomatic.engine.ExecutionContext

/**
 * Action for `action.log`. Writes a single message to the engine log; the
 * message may be wired from upstream data or set as a static literal. Pulses
 * `out` with no data — useful for debugging and audit trails.
 */
class LogAction : Action {

    override val typeId: String = TYPE_ID

    override suspend fun execute(input: ActionInput, context: ExecutionContext): ActionResult {
        val message = input.string("message", default = "")
        context.log(message)
        return ActionResult.passthrough("out")
    }

    companion object {
        const val TYPE_ID = "action.log"
    }
}
