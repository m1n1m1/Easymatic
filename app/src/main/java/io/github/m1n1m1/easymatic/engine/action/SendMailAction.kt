package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.core.service.MailSend
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Multiline
import io.github.m1n1m1.easymatic.domain.model.config.Picker
import io.github.m1n1m1.easymatic.domain.model.config.PickerKind
import io.github.m1n1m1.easymatic.domain.model.config.Wired
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.MailSent
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.actionNode
import kotlinx.serialization.Serializable

/**
 * Config for `action.send_mail`.
 *
 * The recipient fields are **comma-separated strings**, not lists, which is the
 * rule rather than a shortcut: `NodeSchema.formTypeOf` rejects a `List` property
 * outright, and "a parsed spec stored as text" is the sanctioned way round it —
 * the same shape `CompareConfig.source` and `@Ports` use.
 *
 * None of them carries a widget annotation, and there is deliberately no
 * `@EmailAddress` to add. A [Picker] is wrong because the answer set is every
 * address that exists. A chooser *beside* the field — the `@PhoneNumber` shape —
 * is unearned for a subtler reason: that one is cheap because `ACTION_PICK` hands
 * back a contact with a transient grant, and reading an *email* off a contact
 * needs `READ_CONTACTS` held outright. And an address is legible, so a mistyped
 * one is a string you can read back and see is wrong, which puts it on the plain
 * text field's side of the line.
 */
@Serializable
data class SendMailConfig(
    @Label("Account") @Picker(PickerKind.MAIL_ACCOUNT) val accountId: String = "",
    @Label("To") @Wired val to: String = "",
    @Label("Cc") @Wired val cc: String = "",
    @Label("Bcc") @Wired val bcc: String = "",
    @Label("Subject") @Wired val subject: String = "",
    @Label("Body") @Multiline @Wired val body: String = "",
    @Label("Send as HTML") val html: Boolean = false,
)

/**
 * Action for `action.send_mail`. Sends one message through a saved account and
 * reports [MailSent] on its `state` data port.
 *
 * **Declares no permission**, and the absence is deliberate rather than forgotten:
 * `INTERNET` is install-time, is already in the manifest, and gets no `Permissions`
 * constant by the same rule that gives `WAKE_LOCK` and `android.permission.NFC`
 * none. `action.http` declares none for exactly this reason. What can actually
 * stop this node is a password the phone can no longer read, which is not a
 * permission and is reported on the account rather than on the node.
 *
 * Never throws. A missing account, a wrong password, an unreachable host and a
 * malformed recipient all land on `state` with `sent = false` and the reason in
 * `error`, and `out` still pulses — nothing here halts a macro, exactly as a
 * refused HTTP request does not.
 */
class SendMailAction : Action<SendMailConfig, MailSent> {

    override val definition = actionNode<SendMailConfig, MailSent>(
        typeId = "action.send_mail",
        displayName = "Send Email",
        description = "Sends an email through one of your mail accounts",
        category = NodeCategory.NETWORK,
        icon = NodeIcon.MAIL,
        output = dataOut<MailSent>("state"),
    )

    override suspend fun execute(input: SendMailConfig, context: ExecutionContext): NodeOutput<MailSent> {
        val problem = when {
            input.accountId.isBlank() -> "No mail account chosen"
            input.to.isBlank() -> "No recipient"
            else -> null
        }
        if (problem != null) {
            context.log(problem, LogLevel.ERROR)
            return NodeOutput(MailSent(input.to, input.subject, sent = false, error = problem))
        }
        val result = context.mail.send(
            MailSend(
                accountId = input.accountId,
                to = input.to,
                cc = input.cc,
                bcc = input.bcc,
                subject = input.subject,
                body = input.body,
                html = input.html,
            ),
        )
        if (!result.sent) context.log("Could not send: ${result.error}", LogLevel.ERROR)
        return NodeOutput(MailSent(input.to, input.subject, result.sent, result.error))
    }
}
