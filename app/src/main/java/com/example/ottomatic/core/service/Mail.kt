package com.example.ottomatic.core.service

import kotlinx.serialization.Serializable

/**
 * Sending and reading mail, as the engine sees it.
 *
 * Its own facade rather than a corner of [SystemServices] for [Contacts]' reason:
 * that one is write-only, and this both changes things and answers questions.
 *
 * **Suspending**, which is the one place this departs from [SystemServices]. That
 * one is blocking and `action.http` wraps it in `withContext(Dispatchers.IO)` at
 * the call site — fine for a single caller, and four chances to forget here, since
 * a send action, a fetch action, a poll worker and a long-lived IDLE watcher all
 * reach this. [Prompts] and [Waits] are the precedent: a suspending facade whose
 * Android half owns its dispatcher.
 *
 * **Nothing here throws.** Every member answers a result carrying an error string,
 * mirroring [SystemServices.httpRequest] answering `-1` for a request that never
 * happened: a downstream `action.if` sees one shape whether the send worked, the
 * password was wrong, or the phone had no network.
 *
 * An action's facade and never a value node's, and provably so — every member is a
 * network round trip, which is both of the things the pull side may not be.
 */
interface Mail {

    /** Sends one message. */
    suspend fun send(request: MailSend): MailSendResult

    /** Reads messages matching [request] from one mailbox. */
    suspend fun fetch(request: MailFetch): MailFetchResult

    /** Marks, moves or deletes one message. */
    suspend fun update(request: MailUpdate): MailUpdateResult
}

/** Addresses are comma-separated: a `List` config property is not a thing a form can render. */
data class MailSend(
    val accountId: String,
    val to: String,
    val cc: String = "",
    val bcc: String = "",
    val subject: String = "",
    val body: String = "",
    val html: Boolean = false,
)

data class MailSendResult(val sent: Boolean, val error: String = "")

/**
 * [sinceUid] is how the poll and the IDLE watcher ask for "anything newer than
 * what I have already reported" — the server does the filtering, so a mailbox with
 * forty thousand messages costs the same as an empty one. 0 means no lower bound.
 */
data class MailFetch(
    val accountId: String,
    val folder: String = "INBOX",
    val unreadOnly: Boolean = true,
    val fromContains: String = "",
    val subjectContains: String = "",
    val limit: Int = 10,
    val sinceUid: Long = 0,
)

data class MailFetchResult(
    val messages: List<MailMessageData> = emptyList(),
    /**
     * The mailbox's UIDVALIDITY as the server reported it on this connection. The
     * only thing that says a uid means what its holder thinks it means: a server
     * that renumbers a mailbox bumps this, and every uid from before the bump then
     * names a different message or none.
     */
    val uidValidity: Long = 0,
    val error: String = "",
)

/** One message as the transport read it, before a node turns it into an item. */
data class MailMessageData(
    val uid: Long,
    val uidValidity: Long,
    val folder: String,
    val accountId: String,
    /** The bare address, which is what a filter compares. */
    val from: String,
    /** The display name, which is what a notification shows. Blank when the sender gave none. */
    val fromName: String = "",
    val to: String = "",
    val subject: String = "",
    val body: String = "",
    val bodyTruncated: Boolean = false,
    val unread: Boolean = true,
    val hasAttachments: Boolean = false,
    val receivedAtEpochMs: Long = 0,
)

/**
 * What `action.mail_update` does to a message.
 *
 * Carries no `@Label`s, which is a package rule rather than an omission: that
 * annotation lives in `domain`, and `core` may not import it. It costs nothing
 * here because `NodeSchema` prettifies an unlabelled enum name, and "Mark read" /
 * "Mark unread" / "Move" / "Delete" is what these already say.
 *
 * `@Serializable` because an `engine/` config class names it as a property type,
 * and the config form is derived from the serialization descriptor.
 */
@Serializable
enum class MailOp {
    MARK_READ,
    MARK_UNREAD,
    MOVE,
    DELETE,
}

data class MailUpdate(
    val accountId: String,
    val folder: String,
    val uid: Long,
    val uidValidity: Long,
    val op: MailOp,
    val targetFolder: String = "",
)

data class MailUpdateResult(val changed: Boolean, val error: String = "")

/**
 * Sizes and timeouts, in one place so the trigger, the fetch action and the
 * transport cannot drift apart about them.
 */
object MailLimits {

    /**
     * How much of a message body ever reaches a node.
     *
     * Applied where the body is *extracted*, not afterwards — that is the
     * difference between a cap and a truncation, and it is what makes this a
     * memory guarantee rather than a cosmetic one. A trigger's payload rides a
     * `replay = 0, extraBufferCapacity = 64` bus and can be parked for a minute,
     * so a 400 KB HTML mail would be held sixty-four ways over. 8 KB comfortably
     * holds any human-written message and any transactional mail's text part;
     * anything past it sets `bodyTruncated`, so an `action.if` can branch on it
     * rather than a notification quietly showing half a message.
     */
    const val BODY_CHARS = 8_192

    /** Ceiling on `action.fetch_mail`, announced in the run log the way `MAX_ITERATIONS` is. */
    const val MAX_FETCH = 50

    /**
     * Both of these are load-bearing rather than tidy defaults: JavaMail's own are
     * **infinite**, and an infinite read inside a `CoroutineWorker` is a worker
     * that never returns and a WorkManager slot leaked for the life of the process.
     */
    const val CONNECT_TIMEOUT_MS = 15_000

    const val READ_TIMEOUT_MS = 30_000
}

/**
 * No mail available: every call fails closed, naming the one thing the user can do
 * about it.
 *
 * The engine-only default, so a test that builds an [com.example.ottomatic.engine.ExecutionContext]
 * without a mail server sees exactly what a phone with an empty account library
 * reports — which the nodes already have to handle.
 */
object NoMail : Mail {

    override suspend fun send(request: MailSend) = MailSendResult(sent = false, error = UNAVAILABLE)

    override suspend fun fetch(request: MailFetch) = MailFetchResult(error = UNAVAILABLE)

    override suspend fun update(request: MailUpdate) = MailUpdateResult(changed = false, error = UNAVAILABLE)

    private const val UNAVAILABLE = "No mail account is set up on this phone"
}
