package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.permissions.PermissionRequirement
import io.github.m1n1m1.easymatic.core.permissions.Permissions
import io.github.m1n1m1.easymatic.core.permissions.PrerequisiteType
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Multiline
import io.github.m1n1m1.easymatic.domain.model.config.PhoneNumber
import io.github.m1n1m1.easymatic.domain.model.config.Wired
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.SmsSent
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.actionNode
import io.github.m1n1m1.easymatic.engine.resolvePhone
import kotlinx.serialization.Serializable

/**
 * Config for `action.send_sms`; both fields can be wired from upstream data.
 *
 * `to` holds a [io.github.m1n1m1.easymatic.domain.model.PhoneRef] spec: a typed number,
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
