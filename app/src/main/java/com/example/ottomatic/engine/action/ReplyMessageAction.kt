package com.example.ottomatic.engine.action

import com.example.ottomatic.core.permissions.PermissionRequirement
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.MessageReply
import com.example.ottomatic.domain.model.ConversationRef
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Multiline
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.MessageReplied
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode
import kotlinx.serialization.Serializable

/**
 * Config for `action.reply_message`.
 *
 * [conversationId] takes `trigger.message`'s port of the same name, wired straight
 * across with nothing in between — the property, the input port and the trigger's
 * output port all carry the one name on purpose, so the pairing is legible on the
 * canvas. It also accepts the identical field off an `action.break` of the message
 * struct, for a macro that had to break it open for the sender or the text anyway.
 *
 * The name is `conversationId` rather than `conversation` because the struct already
 * has a `conversation`, and that one is the chat's *title* — the thing you would put
 * in a notification. Wiring the readable one into this field would look entirely
 * reasonable and reply to nobody.
 *
 * There is no recipient field here and there cannot be one: what this node needs is
 * not a person but a *conversation Android still has a handle on*, which is not
 * something anybody could type.
 */
@Serializable
data class ReplyMessageConfig(
    @Label("Conversation ID") @Wired val conversationId: String = "",
    @Label("Message") @Multiline @Wired val text: String = "",
)

/**
 * Action for `action.reply_message`. Sends a reply into the conversation a message
 * came from, in whichever app it came from.
 *
 * It sends a **real message** — no UI appears, nothing is opened, and the other
 * person cannot tell it apart from one typed by hand, because there is nothing to
 * tell apart: this presses the messenger's own reply button through the
 * `RemoteInput` a smartwatch would use. One node covers WhatsApp, Signal, Telegram,
 * Messenger and any SMS app, and needs no permission beyond the notification access
 * `trigger.message` already asked for.
 *
 * **The one thing that surprises people: replying needs a live notification.** The
 * handle is the notification itself, so the moment the user opens that chat — or
 * swipes it away, or the app clears it after a sync — there is nothing left to
 * answer, and this reports so rather than failing silently. That makes this a reply
 * mechanism and not a send mechanism; starting a new conversation is
 * `action.send_message`, which cannot send silently at all.
 *
 * A practical consequence worth designing around: put this *before* anything that
 * takes time. A reply after an `action.delay` of five minutes is a reply into a chat
 * the user has very likely read by then.
 *
 * **Never throws.** A blank reference, an unparseable one, a conversation whose
 * notification is gone, an app that offers no reply button and notification access
 * being switched off all land on `state` with `sent = false` and the reason in
 * `error`, and `out` still pulses — nothing here halts a macro, exactly as a refused
 * mail send does not.
 */
class ReplyMessageAction : Action<ReplyMessageConfig, MessageReplied> {

    override val definition = actionNode<ReplyMessageConfig, MessageReplied>(
        typeId = "action.reply_message",
        displayName = "Reply to Message",
        description = "Replies to a message in WhatsApp, Signal, Telegram or another messenger",
        category = NodeCategory.NOTIFICATIONS,
        icon = NodeIcon.CHAT,
        output = dataOut<MessageReplied>("state"),
        // Without notification access there is no listener bound, so nothing was
        // ever tracked and every reply fails — which without this declared would
        // look exactly like a messenger that refuses to be replied to.
        permissions = listOf(
            PermissionRequirement(
                manifestPermission = null,
                type = PrerequisiteType.NOTIFICATION_LISTENER,
                rationaleKey = "notification.listener",
            ),
        ),
    )

    override suspend fun execute(input: ReplyMessageConfig, context: ExecutionContext): NodeOutput<MessageReplied> {
        val parsed = ConversationRef.parse(input.conversationId)
        val problem = when {
            input.conversationId.isBlank() -> "No conversation wired in"
            // Names what it read rather than only that it failed. The likeliest
            // mistake by far is the chat's *title* wired in where its id belongs,
            // and printing "Anna" back is what makes that obvious at a glance.
            parsed == null -> "Not a conversation id: \"${input.conversationId.trim()}\""
            input.text.isBlank() -> "No text to send"
            else -> null
        }
        if (parsed == null || problem != null) {
            context.log(problem.orEmpty(), LogLevel.ERROR)
            return NodeOutput(
                MessageReplied(input.conversationId, input.text, sent = false, error = problem.orEmpty()),
            )
        }
        val result = context.messaging.reply(
            MessageReply(packageName = parsed.packageName, key = parsed.key, text = input.text),
        )
        if (!result.done) context.log(result.error, LogLevel.WARN)
        return NodeOutput(MessageReplied(input.conversationId, input.text, result.done, result.error))
    }
}
