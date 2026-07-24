package com.example.ottomatic.engine.action

import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ActionInput
import com.example.ottomatic.engine.ActionResult
import com.example.ottomatic.engine.ExecutionContext

/**
 * Action for `action.notify`. Posts a device notification with title/text.
 * `text` may be wired from an upstream data edge or set as a static literal.
 */
class NotifyAction : Action {

    override val typeId: String = TYPE_ID

    override suspend fun execute(input: ActionInput, context: ExecutionContext): ActionResult {
        val title = input.config.str("title", default = "Ottomatic")
        val text = input.string("text", default = "Workflow ran")
        context.systemServices.notify(title, text)
        return ActionResult.passthrough("out")
    }

    companion object {
        const val TYPE_ID = "action.notify"
    }
}
