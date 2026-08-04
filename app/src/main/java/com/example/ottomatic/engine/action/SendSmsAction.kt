package com.example.ottomatic.engine.action

import com.example.ottomatic.core.permissions.PermissionRequirement
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Multiline
import com.example.ottomatic.domain.model.config.PhoneNumber
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.SmsSent
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode
import com.example.ottomatic.engine.resolvePhone
import kotlinx.serialization.Serializable

/**
 * Config for `action.send_sms`; both fields can be wired from upstream data.
 *
 * `to` holds a [com.example.ottomatic.domain.model.PhoneRef] spec: a typed number,
 * or a contact resolved when the node runs. See [CallConfig].
 */
@Serializable
data class SendSmsConfig(
    @Label("To") @PhoneNumber @Wired val to: String = "",
    @Label("Body") @Multiline @Wired val body: String = "",
)

/**
 * Action for `action.send_sms`. Sends an SMS to `to` with `body`. Both fields may
 * be wired from upstream data — e.g. replying to a `trigger.sms` sender — or set as
 * static literals. Reports [SmsSent] on its `state` data port, carrying the
 * **resolved** recipient rather than the stored spec.
 */
class SendSmsAction : Action<SendSmsConfig, SmsSent> {

    override val definition = actionNode<SendSmsConfig, SmsSent>(
        typeId = "action.send_sms",
        displayName = "Send SMS",
        description = "Sends an SMS to a number with a body",
        category = NodeCategory.NOTIFICATIONS,
        icon = NodeIcon.SMS,
        output = dataOut<SmsSent>("state"),
        permissions = listOf(
            PermissionRequirement(
                manifestPermission = Permissions.SEND_SMS.manifest,
                type = PrerequisiteType.RUNTIME,
                rationaleKey = "sms.send",
            ),
        ),
    )

    override suspend fun execute(input: SendSmsConfig, context: ExecutionContext): NodeOutput<SmsSent> {
        val to = context.resolvePhone(input.to)
        if (to == null) {
            context.log("No recipient — nothing chosen, or the contact could not be read", LogLevel.ERROR)
            return NodeOutput(SmsSent(to = "", body = input.body, sent = false))
        }
        val ok = context.systemServices.sendSms(to, input.body)
        return NodeOutput(SmsSent(to = to, body = input.body, sent = ok))
    }
}
