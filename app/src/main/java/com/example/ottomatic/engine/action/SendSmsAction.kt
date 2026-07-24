package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.items.SmsSent
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ActionInput
import com.example.ottomatic.engine.ActionResult
import com.example.ottomatic.engine.ExecutionContext

/**
 * Action for `action.send_sms`. Sends an SMS to `to` with `body`. Both fields
 * are EXPR-interpolated so they can be wired from upstream data — e.g. replying
 * to a `trigger.sms` sender. Requires `SEND_SMS`. Reports [SmsSent] on its
 * `state` data port.
 */
class SendSmsAction : Action {

    override val typeId: String = TYPE_ID

    override suspend fun execute(input: ActionInput, context: ExecutionContext): ActionResult {
        val to = input.config.expr("to", default = "")
        val body = input.config.expr("body", default = "")
        val ok = context.systemServices.sendSms(to, body)
        val state = SmsSent(to = to, body = body, sent = ok)
        return ActionResult(execOut = listOf("out"), dataOut = mapOf("state" to Item.of(state)))
    }

    companion object {
        const val TYPE_ID = "action.send_sms"
    }
}
