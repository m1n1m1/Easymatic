package com.example.ottomatic.engine.action

import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ActionInput
import com.example.ottomatic.engine.ActionResult
import com.example.ottomatic.engine.ExecutionContext

/**
 * Action for `action.clipboard`. Sets the clipboard primary clip to `text`
 * (wired from upstream data or a static literal) or clears it when
 * `mode = "clear"`. Passthrough on exec.
 */
class ClipboardAction : Action {

    override val typeId: String = TYPE_ID

    override suspend fun execute(input: ActionInput, context: ExecutionContext): ActionResult {
        val mode = input.config.str("mode", default = "set")
        if (mode == "clear") {
            context.systemServices.clearClipboard()
        } else {
            val text = input.string("text", default = "")
            context.systemServices.setClipboard(text)
        }
        return ActionResult.passthrough("out")
    }

    companion object {
        const val TYPE_ID = "action.clipboard"
    }
}
