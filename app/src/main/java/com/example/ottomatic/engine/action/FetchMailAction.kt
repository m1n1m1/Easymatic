package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.MailFetch
import com.example.ottomatic.core.service.MailLimits
import com.example.ottomatic.core.service.MailMessageData
import com.example.ottomatic.domain.model.MailRef
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.MailFolder
import com.example.ottomatic.domain.model.config.Picker
import com.example.ottomatic.domain.model.config.PickerKind
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.MailMessage
import com.example.ottomatic.domain.model.schema.DateTime
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode
import kotlinx.serialization.Serializable

/** Config for `action.fetch_mail`. Blank filters mean "any". */
@Serializable
data class FetchMailConfig(
    @Label("Account") @Picker(PickerKind.MAIL_ACCOUNT) val accountId: String = "",
    @Label("Folder") @MailFolder @Wired val folder: String = "INBOX",
    @Label("Only unread") val unreadOnly: Boolean = true,
    @Label("From contains") @Wired val fromContains: String = "",
    @Label("Subject contains") @Wired val subjectContains: String = "",
    @Label("How many at most") val limit: Int = DEFAULT_LIMIT,
)

private const val DEFAULT_LIMIT = 10

/**
 * Action for `action.fetch_mail`. Reads a mailbox on demand onto a list.
 *
 * This is the node CLAUDE.md's value-node rule points at when it says a read that
 * is "expensive or failable" is an action's job instead. "How many unread do I
 * have?" is this feeding `transform.list_count`, on the exec wire where a stalled
 * IMAP connection is visible and has somewhere to fail to — rather than a value
 * node pulled afresh before every consumer.
 *
 * The output is a **list port**, which `action.for_each` accepts as-is, so the
 * shape this is built for is fetch → for-each → do something per message. The
 * `ref` each message carries is what `action.mail_update` then acts on.
 *
 * Never throws: an error is an empty list plus one line in the console, and `out`
 * still pulses.
 */
class FetchMailAction : Action<FetchMailConfig, List<MailMessage>> {

    override val definition = actionNode<FetchMailConfig, List<MailMessage>>(
        typeId = "action.fetch_mail",
        displayName = "Fetch Email",
        description = "Reads messages from a mailbox onto a list you can loop over",
        category = NodeCategory.NETWORK,
        icon = NodeIcon.MAIL,
        output = dataOut<List<MailMessage>>("messages", label = "Messages"),
    )

    override suspend fun execute(
        input: FetchMailConfig,
        context: ExecutionContext,
    ): NodeOutput<List<MailMessage>> {
        if (input.accountId.isBlank()) {
            context.log("No mail account chosen", LogLevel.ERROR)
            return NodeOutput(emptyList())
        }
        // Announced rather than silently applied, the way MAX_ITERATIONS is: a cap
        // nobody is told about reads as "that is all the mail there was".
        val limit = input.limit.coerceIn(1, MailLimits.MAX_FETCH)
        if (limit != input.limit) {
            context.log("Asked for ${input.limit} messages; reading $limit, which is the most allowed")
        }
        val result = context.mail.fetch(
            MailFetch(
                accountId = input.accountId,
                folder = input.folder,
                unreadOnly = input.unreadOnly,
                fromContains = input.fromContains,
                subjectContains = input.subjectContains,
                limit = limit,
            ),
        )
        if (result.error.isNotEmpty()) context.log("Could not read mail: ${result.error}", LogLevel.ERROR)
        return NodeOutput(result.messages.map { it.toItem() })
    }
}

/**
 * The transport's reading of a message, as the graph's item.
 *
 * The `ref` is minted here rather than carried through the facade because it is a
 * *graph* concept — the handle a downstream node holds — where everything else on
 * [MailMessageData] is what the server said.
 */
internal fun MailMessageData.toItem(): MailMessage = MailMessage(
    ref = MailRef.format(accountId = accountId, folder = folder, uidValidity = uidValidity, uid = uid),
    from = from,
    fromName = fromName,
    to = to,
    subject = subject,
    body = body,
    bodyTruncated = bodyTruncated,
    unread = unread,
    hasAttachments = hasAttachments,
    folder = folder,
    accountId = accountId,
    receivedAt = DateTime(receivedAtEpochMs),
)
