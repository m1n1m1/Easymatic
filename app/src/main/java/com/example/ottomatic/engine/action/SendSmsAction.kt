package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Multiline
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.SmsSent
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode
import kotlinx.serialization.Serializable

/** Config for `action.send_sms`; both fields can be wired from upstream data. */
@Serializable
data class SendSmsConfig(
    @Label("To (phone number)") @Wired val to: String = "",
    @Label("Body") @Multiline @Wired val body: String = "",
)

/**
 * Action for `action.send_sms`. Sends an SMS to `to` with `body`. Both fields
 * may be wired from upstream data — e.g. replying to a `trigger.sms` sender —
 * or set as static literals. Requires `SEND_SMS`. Reports [SmsSent] on its
 * `state` data port.
 */
class SendSmsAction : Action<SendSmsConfig, SmsSent> {

    override val definition = actionNode<SendSmsConfig, SmsSent>(
        typeId = "action.send_sms",
        displayName = "Send SMS",
        description = "Sends an SMS to a number with a body",
        category = NodeCategory.NOTIFICATIONS,
        icon = NodeIcon.SMS,
        output = dataOut<SmsSent>("state"),
    )

    override suspend fun execute(input: SendSmsConfig, context: ExecutionContext): NodeOutput<SmsSent> {
        val ok = context.systemServices.sendSms(input.to, input.body)
        return NodeOutput(SmsSent(to = input.to, body = input.body, sent = ok))
    }
}
