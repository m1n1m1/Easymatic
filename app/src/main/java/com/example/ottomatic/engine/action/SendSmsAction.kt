package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.dataInPort
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.SmsSent
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode

data class SendSmsInput(val to: String, val body: String)

/**
 * Action for `action.send_sms`. Sends an SMS to `to` with `body`. Both fields
 * may be wired from upstream data — e.g. replying to a `trigger.sms` sender —
 * or set as static literals. Requires `SEND_SMS`. Reports [SmsSent] on its
 * `state` data port.
 */
class SendSmsAction : Action<SendSmsInput, SmsSent> {

    override val definition = actionNode<SendSmsInput, SmsSent>(
        typeId = "action.send_sms",
        displayName = "Send SMS",
        description = "Sends an SMS to a number with a body",
        category = NodeCategory.NOTIFICATIONS,
        iconKey = "sms",
        dataInputs = listOf(
            dataInPort<String>("to"),
            dataInPort<String>("body"),
        ),
        dataOutputs = listOf(dataOut<SmsSent>("state")),
        configFields = listOf(
            ConfigField(
                key = "to",
                label = "To (phone number)",
                type = ConfigFieldType.STR,
                defaultValue = "",
            ),
            ConfigField(
                key = "body",
                label = "Body",
                type = ConfigFieldType.MULTILINE,
                defaultValue = "",
            ),
        ),
        decode = { input -> SendSmsInput(input.text("to"), input.text("body")) },
        encodeData = { state -> mapOf("state" to Item.of(state)) },
    )

    override suspend fun execute(input: SendSmsInput, context: ExecutionContext): NodeOutput<SmsSent> {
        val ok = context.systemServices.sendSms(input.to, input.body)
        return NodeOutput(SmsSent(to = input.to, body = input.body, sent = ok))
    }
}
