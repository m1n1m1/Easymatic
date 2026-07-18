package com.example.ottomatic.engine.action

import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ActionInput
import com.example.ottomatic.engine.ActionResult
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.interpolate

/**
 * Action for `action.notify`. Posts a device notification with title/text
 * that may reference payload values via `{{field}}`.
 */
class NotifyAction : Action {

    override val typeId: String = TYPE_ID

    override suspend fun execute(input: ActionInput, context: ExecutionContext): ActionResult {
        val title = interpolate(input.node.config["title"] ?: "Ottomatic", input.payload)
        val text = interpolate(input.node.config["text"] ?: "Workflow ran", input.payload)
        context.systemServices.notify(title, text)
        return ActionResult.passthrough(input)
    }

    companion object {
        const val TYPE_ID = "action.notify"
    }
}
