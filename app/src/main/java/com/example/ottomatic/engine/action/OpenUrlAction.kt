package com.example.ottomatic.engine.action

import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ActionInput
import com.example.ottomatic.engine.ActionResult
import com.example.ottomatic.engine.ExecutionContext

/**
 * Action for `action.open_url`. Opens a URL in the default handler (browser or
 * app via intent). `url` (EXPR) is interpolated against the runtime data
 * context. Pulses `out`; on failure (no handler) still pulses `out` but logs.
 */
class OpenUrlAction : Action {

    override val typeId: String = TYPE_ID

    override suspend fun execute(input: ActionInput, context: ExecutionContext): ActionResult {
        val url = input.config.expr("url", default = "")
        val ok = context.systemServices.openUrl(url)
        if (!ok) context.log("Open url failed: $url")
        return ActionResult.passthrough("out")
    }

    companion object {
        const val TYPE_ID = "action.open_url"
    }
}
