package io.github.m1n1m1.easymatic.engine.trigger

import kotlinx.serialization.Serializable

/** How `trigger.mail` watches a mailbox. */
@Serializable
enum class MailWatchMode {
    /**
     * Push where the server offers it, and a periodic check underneath either way.
     * Near-instant on Gmail, iCloud, Fastmail and any Dovecot server.
     */
    AUTOMATIC,

    /** Never hold a connection open; check on a schedule. */
    POLL,
}

/**
 * What `TriggerHost.armMailWatch` needs in order to open a connection.
 *
 * The filters a user typed are **deliberately not here**. `fromContains` and
 * `subjectContains` are predicates the trigger applies per event, so editing one
 * takes effect with no re-arm signal to invent — the same call `trigger.sms` makes
 * about its sender filter, and for the same reason.
 *
 * [unreadOnly] is the exception, and the distinction is worth keeping straight: it
 * is an IMAP `SEARCH UNSEEN` term the *server* evaluates, so it is not a predicate
 * at all but a cheaper query. Changing it changes the connection, so it belongs to
 * the arm.
 */
data class MailWatchSpec(
    val folder: String = "INBOX",
    val unreadOnly: Boolean = true,
    val mode: MailWatchMode = MailWatchMode.AUTOMATIC,
    val intervalMinutes: Long = DEFAULT_MAIL_POLL_MINUTES,
)

/** WorkManager's own floor; anything shorter is silently rounded up by the platform. */
const val DEFAULT_MAIL_POLL_MINUTES = 15L

/**
 * The payload keys a mail bus event carries.
 *
 * Declared once and read by both halves — the producers in `data/mail/` and
 * `trigger.mail`'s mapping — because two spellings of `"subject"` would compile
 * perfectly and produce a message with no subject.
 */
object MailPayload {
    const val REF = "ref"
    const val FROM = "from"
    const val FROM_NAME = "fromName"
    const val TO = "to"
    const val SUBJECT = "subject"
    const val BODY = "body"
    const val BODY_TRUNCATED = "bodyTruncated"
    const val UNREAD = "unread"
    const val HAS_ATTACHMENTS = "hasAttachments"
    const val FOLDER = "folder"
    const val ACCOUNT_ID = "accountId"
    const val RECEIVED_AT = "receivedAt"
}
