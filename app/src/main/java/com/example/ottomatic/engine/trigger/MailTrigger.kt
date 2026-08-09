package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Picker
import com.example.ottomatic.domain.model.config.PickerKind
import com.example.ottomatic.domain.model.config.VisibleWhen
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.MailMessage
import com.example.ottomatic.domain.model.schema.DateTime
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/**
 * Config for `trigger.mail`.
 *
 * Blank [fromContains] and [subjectContains] mean "any", the way a blank SSID
 * means any network and a blank tag id means any tag.
 *
 * [folder] is a plain typed field rather than a picker, which is the opposite call
 * from [accountId] on the same form and worth keeping straight. An account id is a
 * UUID — opaque, so a typed one names nothing while looking correct. A folder name
 * is legible: `INBX` reads back as obviously wrong. And listing folders needs a
 * working authenticated connection, so a picker would be unfillable exactly when
 * the account is misconfigured — `@WifiNetwork`'s "a chooser can only offer what
 * is reachable right now" argument, in its second form.
 *
 * [mode] exists because AUTOMATIC quietly falling back to a poll would be
 * invisible, and how often the phone wakes to check mail is a battery decision the
 * user is entitled to force.
 */
@Serializable
data class MailTriggerConfig(
    @Label("Account") @Picker(PickerKind.MAIL_ACCOUNT) val accountId: String = "",
    @Label("Folder") val folder: String = "INBOX",
    @Label("From contains") val fromContains: String = "",
    @Label("Subject contains") val subjectContains: String = "",
    @Label("Only unread") val unreadOnly: Boolean = true,
    @Label("How to watch") val mode: MailWatchMode = MailWatchMode.AUTOMATIC,
    @Label("Check every (minutes, minimum 15)")
    @VisibleWhen("mode", "POLL")
    val intervalMinutes: Long = DEFAULT_MAIL_POLL_MINUTES,
)

/**
 * Trigger for `trigger.mail`. Starts a macro when a message arrives in a mailbox.
 *
 * **Declares no permission**, deliberately: `INTERNET` is install-time and gets no
 * `Permissions` constant, by the rule the manifest states for `android.permission.NFC`.
 * What actually stops this trigger is a deleted account or a password the phone can
 * no longer read, and neither is a permission — both are reported into the macro's
 * own console at arm time, where somebody wondering why nothing fires will see them.
 *
 * **There is no `value.mail`, and both of CLAUDE.md's exclusions apply.** Mail
 * arrival is an *event* with no resting value — there is no "which message am I on
 * right now" any more than there is for a tag tap. And a mail read is *expensive
 * and failable*: a value is pulled just before every consuming node, so two
 * `action.if`s comparing an unread count would be two authenticated IMAP round
 * trips, each able to stall for the full read timeout inside a pull. That is what
 * `action.fetch_mail` is for, on the exec wire where the latency is visible.
 *
 * The filters are applied **per event** rather than passed into the arm, which is
 * the call `trigger.sms` makes about its sender: a filter is only a predicate,
 * where a connection has to be opened, so editing one takes effect with no re-arm
 * signal to invent. `unreadOnly` is the exception because the server evaluates it.
 */
class MailTrigger : Trigger<MailTriggerConfig, MailMessage> {

    override val definition = triggerNode<MailTriggerConfig, MailMessage>(
        typeId = "trigger.mail",
        displayName = "Email Received",
        description = "Starts when an email arrives in a mailbox you are watching",
        category = NodeCategory.MESSAGING,
        icon = NodeIcon.MAIL,
        output = dataOut<MailMessage>("mail", label = "Mail"),
    )

    override fun activate(
        config: MailTriggerConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<MailMessage>> = flow {
        val account = host.mailAccount(config.accountId)
        if (account == null) {
            // Stays unarmed rather than watching nothing, exactly as an
            // unresolvable geofence place does — and says so, because a macro that
            // looks armed and is not is precisely what `report` exists for.
            host.report(
                node,
                if (config.accountId.isBlank()) {
                    "No mail account chosen, so this trigger cannot fire"
                } else {
                    "That mail account has been deleted, so this trigger cannot fire"
                },
                LogLevel.WARN,
            )
            return@flow
        }
        host.report(node, "Watching ${account.address} · ${config.folder}")
        val handle = host.armMailWatch(
            nodeId = node.id,
            accountId = config.accountId,
            spec = MailWatchSpec(
                folder = config.folder,
                unreadOnly = config.unreadOnly,
                mode = config.mode,
                intervalMinutes = config.intervalMinutes,
            ),
            // Everything interesting about a mail connection happens after arming
            // — a server with no push, a socket that keeps dropping, a mailbox the
            // server renumbered — and this is the only route from there back to
            // the console somebody will actually read.
            onReport = { message, level -> host.report(node, message, level) },
        )
        try {
            host.busEventsFor(node.id)
                .filter { it.source == TriggerSource.MAIL && it.triggerNodeId == node.id }
                .map { it.toMailMessage() }
                .filter { matches(config, it) }
                .collect { emit(NodeOutput(it)) }
        } finally {
            handle.cancel()
        }
    }
}

/**
 * Whether this message is one the node asked for.
 *
 * Case-insensitive contains on both, and blank means any. The sender is matched on
 * the **address** rather than the display name, because a display name is chosen
 * by whoever sent the mail and is therefore the half an unwanted sender controls.
 */
internal fun matches(config: MailTriggerConfig, message: MailMessage): Boolean {
    val fromOk = config.fromContains.isBlank() ||
        message.from.contains(config.fromContains, ignoreCase = true) ||
        message.fromName.contains(config.fromContains, ignoreCase = true)
    val subjectOk = config.subjectContains.isBlank() ||
        message.subject.contains(config.subjectContains, ignoreCase = true)
    return fromOk && subjectOk
}

/** Maps a mail bus event to the typed item. Payload keys are [MailPayload]'s. */
internal fun TriggerEvent.toMailMessage(): MailMessage = MailMessage(
    ref = payload[MailPayload.REF].orEmpty(),
    from = payload[MailPayload.FROM].orEmpty(),
    fromName = payload[MailPayload.FROM_NAME].orEmpty(),
    to = payload[MailPayload.TO].orEmpty(),
    subject = payload[MailPayload.SUBJECT].orEmpty(),
    body = payload[MailPayload.BODY].orEmpty(),
    bodyTruncated = payload[MailPayload.BODY_TRUNCATED]?.toBooleanStrictOrNull() ?: false,
    unread = payload[MailPayload.UNREAD]?.toBooleanStrictOrNull() ?: true,
    hasAttachments = payload[MailPayload.HAS_ATTACHMENTS]?.toBooleanStrictOrNull() ?: false,
    folder = payload[MailPayload.FOLDER].orEmpty(),
    accountId = payload[MailPayload.ACCOUNT_ID].orEmpty(),
    // Falls back to when the event was raised rather than to the epoch: a server
    // that reports no date at all should read as "just now", not as 1970.
    receivedAt = payload[MailPayload.RECEIVED_AT]
        ?.toLongOrNull()
        ?.takeIf { it > 0 }
        ?.let { DateTime(it) }
        ?: timestamp,
)
