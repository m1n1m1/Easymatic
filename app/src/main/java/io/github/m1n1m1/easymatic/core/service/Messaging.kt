package io.github.m1n1m1.easymatic.core.service

import kotlinx.serialization.Serializable

/**
 * Answering a messenger, as the engine sees it.
 *
 * Its own facade rather than a corner of [SystemServices] for [Mail]'s reason: that
 * one is write-only, and this both acts and reports.
 *
 * **Not suspending**, which is where it parts company with [Mail] and follows
 * [SystemServices] instead: every call here is one binder round trip to a
 * `PendingIntent` the messenger already prepared, not a network one. There is no
 * dispatcher for an Android half to own.
 *
 * The mechanism behind it is the same for every app, which is the whole reason these
 * nodes are not vendor-shaped: a messenger that supports watch or Auto quick-reply
 * attaches a reply action carrying a `RemoteInput` to the notification it posts, and
 * filling that in sends a genuine message with no UI. WhatsApp, Signal, Telegram,
 * Messenger and any SMS app all qualify, and a fifth needs no code.
 *
 * Its one hard limit is worth stating here rather than only on the node: this can
 * **reply**, never send. The handle is a live notification, so a conversation the
 * user has already opened — and therefore dismissed — can no longer be answered.
 *
 * **Nothing here throws.** Every member answers a result carrying an error string,
 * mirroring [Mail] and [SystemServices.httpRequest]: a downstream `action.if` sees
 * one shape whether the reply worked, notification access was off, or the chat had
 * been read a second earlier.
 *
 * An action's facade and never a value node's. Reading is not what it does — every
 * member changes something on another app's behalf.
 */
interface Messaging {

    /** Sends [MessageReply.text] into the conversation the reply names. */
    fun reply(request: MessageReply): MessagingResult

    /** Runs one of the notification's own actions, or dismisses it. */
    fun act(request: NotificationAct): MessagingResult
}

/**
 * The parts of a [io.github.m1n1m1.easymatic.domain.model.ConversationRef], already
 * parsed.
 *
 * The node parses and this takes the pieces, which is `action.mail_update`'s split
 * and keeps `core` from needing to know what a reference looks like. [packageName]
 * is carried as well as [key] even though the key alone identifies the
 * notification: it is what lets a stale reference be reported as naming the wrong
 * app rather than merely naming nothing.
 */
data class MessageReply(
    val packageName: String,
    val key: String,
    val text: String,
)

/** As [MessageReply], for the operations that do not send anything. */
data class NotificationAct(
    val packageName: String,
    val key: String,
    val op: NotificationOp,
    val label: String = "",
)

/**
 * What `action.notification_action` does to a notification.
 *
 * Carries no `@Label`s, which is the package rule [MailOp] follows rather than an
 * omission: that annotation lives in `domain`, and `core` may not import it.
 *
 * [MARK_READ] is not a label guess. Android has a documented semantic action for it,
 * so the right button is found by meaning rather than by matching English text that
 * changes with the phone's language. [RUN_ACTION] is the escape hatch for everything
 * that has no such constant — "Mute", "Like", "Snooze" — and there the label is all
 * there is to go on.
 */
@Serializable
enum class NotificationOp {
    MARK_READ,
    RUN_ACTION,
    DISMISS,
}

data class MessagingResult(val done: Boolean, val error: String = "")

/**
 * No notification access: every call fails closed, naming the one thing the user can
 * do about it.
 *
 * The engine-only default, so a test that builds an
 * [io.github.m1n1m1.easymatic.engine.ExecutionContext] without a listener sees exactly
 * what a phone that has never granted notification access reports — which the nodes
 * already have to handle.
 */
object NoMessaging : Messaging {

    override fun reply(request: MessageReply) = MessagingResult(done = false, error = UNAVAILABLE)

    override fun act(request: NotificationAct) = MessagingResult(done = false, error = UNAVAILABLE)

    private const val UNAVAILABLE = "Notification access is not switched on for Easymatic"
}
