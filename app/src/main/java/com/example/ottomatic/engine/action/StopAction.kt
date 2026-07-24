package com.example.ottomatic.engine.action

import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ActionInput
import com.example.ottomatic.engine.ActionResult
import com.example.ottomatic.engine.ExecutionContext

/**
 * Action for `action.stop`. Halts the current execution chain: the
 * [com.example.ottomatic.engine.WorkflowExecutor] stops following `execOut`
 * ports after this node runs. Optionally logs a reason (wired from upstream
 * data or a static literal) before halting. Pairs with
 * `trigger.macro_finished`.
 *
 * The halt is scoped to the current trigger's traversal — sibling chains
 * triggered by independent events are unaffected.
 */
class StopAction : Action {

    override val typeId: String = TYPE_ID

    override suspend fun execute(input: ActionInput, context: ExecutionContext): ActionResult {
        val reason = input.string("reason", default = "")
        if (reason.isNotBlank()) context.log("Stop: $reason")
        return ActionResult(execOut = listOf("out"), halt = true)
    }

    companion object {
        const val TYPE_ID = "action.stop"
    }
}
