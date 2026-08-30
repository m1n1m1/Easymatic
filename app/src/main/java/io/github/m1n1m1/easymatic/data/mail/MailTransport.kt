package io.github.m1n1m1.easymatic.data.mail

import io.github.m1n1m1.easymatic.core.service.MailFetch
import io.github.m1n1m1.easymatic.core.service.MailFetchResult
import io.github.m1n1m1.easymatic.core.service.MailLimits
import io.github.m1n1m1.easymatic.core.service.MailMessageData
import io.github.m1n1m1.easymatic.core.service.MailOp
import io.github.m1n1m1.easymatic.core.service.MailSend
import io.github.m1n1m1.easymatic.core.service.MailUpdate
import io.github.m1n1m1.easymatic.domain.model.MailAccount
import io.github.m1n1m1.easymatic.domain.model.MailSecurity
import java.net.UnknownHostException
import java.util.Date
import java.util.Properties
import javax.mail.AuthenticationFailedException
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.MessagingException
import javax.mail.Session
import javax.mail.Store
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * The one place in the app that speaks SMTP and IMAP — and therefore the only
 * file that imports `javax.mail`.
 *
 * **Blocking**, deliberately: callers wrap it. Four of them will (`AndroidMail`,
 * the poll worker, the IDLE watcher and the account editor's connection test), and
 * a suspending transport would have each of them believing the dispatcher was
 * somebody else's problem. The suspending boundary is [io.github.m1n1m1.easymatic.core.service.Mail],
 * one layer up, which is where it belongs.
 *
 * One transport shared by everything for the reason
 * [io.github.m1n1m1.easymatic.domain.model.TimeOfDay] is shared by the schedule trigger
 * and its picker: two readings of one message would eventually disagree, and the
 * one that disagreed would be the one nobody was looking at.
 */
@Suppress("TooManyFunctions") // One member per protocol operation, plus their readers. IMAP sets the count.
object MailTransport {

    /**
     * Signs in to both protocols and hangs up. Returns null when that worked, or a
     * sentence to show the user when it did not.
     *
     * This is what the account editor's **Test connection** button calls, and it
     * earns its place by *when* it runs rather than by what it does: without it,
     * the first thing that ever tries this password is a macro, at three in the
     * morning, reporting into a console nobody is reading. Both protocols are
     * probed because they fail independently — a provider that has not had IMAP
     * switched on will send perfectly well and receive nothing at all.
     */
    fun probe(account: MailAccount, password: String): String? = when {
        !account.isComplete -> "This account is missing an address or a server name"
        password.isEmpty() -> "No password stored for this account"
        else -> probeSmtp(account, password) ?: probeImap(account, password)
    }

    private fun probeSmtp(account: MailAccount, password: String): String? = runCatching {
        val transport = Session.getInstance(smtpProperties(account)).getTransport(SMTP)
        transport.connect(account.smtpHost, account.smtpPort, account.effectiveUsername, password)
        transport.close()
        null
    }.getOrElse { failure -> "Sending: " + explain(failure) }

    private fun probeImap(account: MailAccount, password: String): String? = runCatching {
        val store = Session.getInstance(imapProperties(account)).getStore(IMAP)
        store.connect(account.imapHost, account.imapPort, account.effectiveUsername, password)
        store.close()
        null
    }.getOrElse { failure -> "Receiving: " + explain(failure) }

    /**
     * Sends one message. Throws on failure — [AndroidMail] is what turns that into
     * a result, because it is the layer that promised never to throw.
     *
     * The charset is stated on every part rather than left to the platform
     * default. Without it a subject with an umlaut in it arrives as mojibake on
     * some servers and correctly on others, which is the worst kind of bug to be
     * told about second-hand.
     */
    fun sendMessage(account: MailAccount, password: String, request: MailSend) {
        val session = Session.getInstance(smtpProperties(account))
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(account.address))
            setRecipients(Message.RecipientType.TO, parseAddresses(request.to))
            if (request.cc.isNotBlank()) setRecipients(Message.RecipientType.CC, parseAddresses(request.cc))
            if (request.bcc.isNotBlank()) setRecipients(Message.RecipientType.BCC, parseAddresses(request.bcc))
            setSubject(request.subject, CHARSET)
            if (request.html) {
                setContent(request.body, "text/html; charset=$CHARSET")
            } else {
                setText(request.body, CHARSET)
            }
            sentDate = Date()
        }
        val transport = session.getTransport(SMTP)
        transport.connect(account.smtpHost, account.smtpPort, account.effectiveUsername, password)
        try {
            transport.sendMessage(message, message.allRecipients)
        } finally {
            transport.close()
        }
    }

    /**
     * Every mailbox on the server that can hold messages, by full name.
     *
     * Full names, not display names, because the full name is what every other
     * call here takes and what is stored in the node — `[Gmail]/All Mail` rather
     * than `All Mail`. Folders that only hold other folders are dropped: they
     * cannot be watched or fetched from, so offering one would be offering a
     * guaranteed empty result.
     *
     * Sorted with INBOX pinned first. It is the answer nine times in ten and is
     * the one name that is the same on every server in the world, so alphabetising
     * it into the middle of a Gmail account's bracketed folders would bury the
     * obvious choice.
     */
    fun folders(account: MailAccount, password: String): List<String> {
        val store = Session.getInstance(imapProperties(account)).getStore(IMAP)
        store.connect(account.imapHost, account.imapPort, account.effectiveUsername, password)
        try {
            return store.defaultFolder.list("*")
                .filter { folder -> runCatching { folder.type and Folder.HOLDS_MESSAGES != 0 }.getOrDefault(false) }
                .map { it.fullName }
                .sortedWith(compareBy({ !it.equals(INBOX, ignoreCase = true) }, { it.lowercase() }))
        } finally {
            runCatching { store.close() }
        }
    }

    /**
     * Reads messages from one mailbox. Throws on failure; [AndroidMail] converts.
     *
     * Two ways in, and which one is used is what keeps a large mailbox cheap.
     * [MailFetch.sinceUid] above zero asks the **server** for everything newer than
     * the caller's high-water mark, so a forty-thousand-message inbox costs the
     * same as an empty one — that is the poll's path, on every run after the first.
     * Zero falls back to the last [MailFetch.limit] by sequence number, which is
     * what a first sync and `action.fetch_mail` want.
     *
     * The folder is opened **read-only**. Reading mail must not mark it read: that
     * is `action.mail_update`'s job, on the exec wire, where the user asked for it.
     */
    fun listMessages(account: MailAccount, password: String, request: MailFetch): MailFetchResult {
        val store = Session.getInstance(imapProperties(account)).getStore(IMAP)
        store.connect(account.imapHost, account.imapPort, account.effectiveUsername, password)
        try {
            val folder = store.getFolder(request.folder)
            folder.open(Folder.READ_ONLY)
            try {
                return read(folder, request, account.id)
            } finally {
                runCatching { folder.close(false) }
            }
        } finally {
            runCatching { store.close() }
        }
    }

    private fun read(folder: Folder, request: MailFetch, accountId: String): MailFetchResult {
        val uidFolder = folder as UIDFolder
        val validity = uidFolder.uidValidity
        val candidates = when {
            request.sinceUid > 0 -> uidFolder.getMessagesByUID(request.sinceUid + 1, UIDFolder.LASTUID)
            folder.messageCount == 0 -> emptyArray()
            else -> {
                val first = maxOf(1, folder.messageCount - request.limit.coerceAtMost(MailLimits.MAX_FETCH) + 1)
                folder.getMessages(first, folder.messageCount)
            }
        }
        // One round trip for the envelopes and flags of every candidate instead of
        // one per message. Bodies are still fetched individually, which is why the
        // filters below run before the mapping rather than after it.
        if (candidates.isNotEmpty()) {
            folder.fetch(
                candidates,
                FetchProfile().apply {
                    add(FetchProfile.Item.ENVELOPE)
                    add(FetchProfile.Item.FLAGS)
                    add(UIDFolder.FetchProfileItem.UID)
                },
            )
        }
        val messages = candidates
            .filter { matches(it, request) }
            .takeLast(request.limit.coerceAtMost(MailLimits.MAX_FETCH))
            .map { toMessageData(it, uidFolder.getUID(it), validity, folder.fullName, accountId) }
        return MailFetchResult(messages = messages, uidValidity = validity)
    }

    private fun matches(message: Message, request: MailFetch): Boolean = runCatching {
        val unreadOk = !request.unreadOnly || !message.isSet(Flags.Flag.SEEN)
        val fromOk = request.fromContains.isBlank() ||
            message.from.orEmpty().any { it.toString().contains(request.fromContains, ignoreCase = true) }
        val subjectOk = request.subjectContains.isBlank() ||
            MailBodyText.decodeHeader(message.subject).contains(request.subjectContains, ignoreCase = true)
        unreadOk && fromOk && subjectOk
    }.getOrDefault(false)

    /**
     * One message, read once.
     *
     * Shared by the fetch action, the poll worker and the IDLE watcher, for the
     * reason [io.github.m1n1m1.easymatic.domain.model.TimeOfDay] is shared by the
     * schedule trigger and its picker: two readings of one message would eventually
     * disagree, and the one that disagreed would be the one nobody was watching.
     */
    fun toMessageData(
        message: Message,
        uid: Long,
        uidValidity: Long,
        folder: String,
        accountId: String,
    ): MailMessageData {
        val sender = runCatching { message.from?.firstOrNull() as? InternetAddress }.getOrNull()
        val body = MailBodyText.extract(message)
        return MailMessageData(
            uid = uid,
            uidValidity = uidValidity,
            folder = folder,
            accountId = accountId,
            from = sender?.address.orEmpty(),
            fromName = MailBodyText.decodeHeader(sender?.personal),
            to = runCatching { message.getRecipients(Message.RecipientType.TO)?.joinToString() }
                .getOrNull()
                .orEmpty(),
            subject = MailBodyText.decodeHeader(runCatching { message.subject }.getOrNull()),
            body = body.text,
            bodyTruncated = body.truncated,
            unread = runCatching { !message.isSet(Flags.Flag.SEEN) }.getOrDefault(true),
            hasAttachments = MailBodyText.hasAttachments(message),
            // Some servers report no received date at all; the sent date is the
            // honest fallback, and zero would render as 1970 in a notification.
            receivedAtEpochMs = runCatching { (message.receivedDate ?: message.sentDate)?.time }
                .getOrNull()
                ?: 0L,
        )
    }

    /**
     * Marks, moves or deletes one message. Throws on failure; [AndroidMail]
     * converts. Returns false when the uid named nothing — moved, expunged, or
     * from before the mailbox was renumbered.
     *
     * The UIDVALIDITY is checked **before** anything is touched, and that check is
     * the whole reason the reference carries it: a uid from before a renumbering
     * names a different message, so acting on it would mark, move or delete
     * something the user never pointed at.
     */
    fun applyOp(account: MailAccount, password: String, request: MailUpdate): Boolean {
        val store = Session.getInstance(imapProperties(account)).getStore(IMAP)
        store.connect(account.imapHost, account.imapPort, account.effectiveUsername, password)
        try {
            val folder = store.getFolder(request.folder)
            folder.open(Folder.READ_WRITE)
            try {
                val message = (folder as UIDFolder)
                    .takeIf { it.uidValidity == request.uidValidity }
                    ?.getMessageByUID(request.uid)
                message?.let { apply(store, folder, it, request) }
                return message != null
            } finally {
                // Expunge on close only for a delete: closing with expunge = true
                // after a flag change would also purge anything another client had
                // marked deleted in this mailbox, which is not this node's business.
                runCatching { folder.close(request.op == MailOp.DELETE) }
            }
        } finally {
            runCatching { store.close() }
        }
    }

    private fun apply(store: Store, folder: Folder, message: Message, request: MailUpdate) {
        when (request.op) {
            MailOp.MARK_READ -> message.setFlag(Flags.Flag.SEEN, true)
            MailOp.MARK_UNREAD -> message.setFlag(Flags.Flag.SEEN, false)
            MailOp.MOVE -> moveTo(store, folder, message, request.targetFolder)
            // A copy into Trash and a delete flag, rather than a bare \Deleted:
            // on Gmail and most modern servers the flag alone hides the message
            // with no way back, where "delete" to a person means "it is in the
            // bin". Falling back to the flag is for the servers with no such
            // folder, where there is nowhere else for it to go.
            MailOp.DELETE -> trash(store, folder, message)
        }
    }

    private fun moveTo(store: Store, folder: Folder, message: Message, target: String) {
        val destination = store.getFolder(target)
        if (!destination.exists()) destination.create(Folder.HOLDS_MESSAGES)
        folder.copyMessages(arrayOf(message), destination)
        message.setFlag(Flags.Flag.DELETED, true)
    }

    private fun trash(store: Store, folder: Folder, message: Message) {
        val trash = TRASH_NAMES.firstOrNull { name ->
            runCatching { store.getFolder(name).exists() }.getOrDefault(false)
        }
        if (trash != null && !folder.fullName.equals(trash, ignoreCase = true)) {
            folder.copyMessages(arrayOf(message), store.getFolder(trash))
        }
        message.setFlag(Flags.Flag.DELETED, true)
    }

    /**
     * Splits a comma-separated recipient field.
     *
     * `strict = false` is deliberate: the strict parser rejects a bare `Ann
     * <a@b.c>` without a fully quoted display name, which is exactly what somebody
     * pastes out of their address book. A genuinely malformed address still throws,
     * and lands on the node's `error` field naming itself.
     */
    fun parseAddresses(field: String): Array<InternetAddress> =
        InternetAddress.parse(field.trim(), false)

    /**
     * Properties for an outgoing connection.
     *
     * The two timeouts are the load-bearing lines and are easy to read as
     * housekeeping. JavaMail's defaults are **infinite**: a server that accepts a
     * socket and then says nothing would hang the caller for ever, which inside a
     * `CoroutineWorker` means a worker that never returns and a WorkManager slot
     * leaked for the life of the process.
     */
    fun smtpProperties(account: MailAccount): Properties = Properties().apply {
        put("mail.smtp.host", account.smtpHost)
        put("mail.smtp.port", account.smtpPort.toString())
        put("mail.smtp.auth", "true")
        put("mail.smtp.connectiontimeout", MailLimits.CONNECT_TIMEOUT_MS.toString())
        put("mail.smtp.timeout", MailLimits.READ_TIMEOUT_MS.toString())
        put("mail.smtp.writetimeout", MailLimits.READ_TIMEOUT_MS.toString())
        when (account.smtpSecurity) {
            // Implicit TLS: the socket is wrapped before a byte of SMTP is spoken.
            MailSecurity.TLS -> put("mail.smtp.ssl.enable", "true")
            // Opens in the clear and upgrades. `required` is what stops a server
            // that quietly does not offer STARTTLS from being talked to in plain
            // text — the failure mode this setting exists to prevent is silent.
            MailSecurity.STARTTLS -> {
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
            }
            MailSecurity.NONE -> Unit
        }
    }

    /**
     * Properties for an incoming connection. See [smtpProperties] on the timeouts.
     *
     * [readTimeoutMs] is a parameter for one reason, and it is not a tuning knob:
     * `mail.imap.timeout` is the socket read timeout, and **`IMAPFolder.idle()`
     * spends its whole life blocked on a socket read waiting for the server to
     * speak**. A healthy, quiet mailbox says nothing for hours, so the ordinary
     * 30-second timeout does not protect an IDLE connection — it kills one every
     * thirty seconds, which reads exactly like a flaky server. A watcher therefore
     * passes something longer than its own re-issue interval; see
     * [MailIdleWatcher.IDLE_SOCKET_TIMEOUT_MS].
     */
    fun imapProperties(
        account: MailAccount,
        readTimeoutMs: Int = MailLimits.READ_TIMEOUT_MS,
    ): Properties = Properties().apply {
        put("mail.imap.host", account.imapHost)
        put("mail.imap.port", account.imapPort.toString())
        put("mail.imap.connectiontimeout", MailLimits.CONNECT_TIMEOUT_MS.toString())
        put("mail.imap.timeout", readTimeoutMs.toString())
        when (account.imapSecurity) {
            MailSecurity.TLS -> put("mail.imap.ssl.enable", "true")
            MailSecurity.STARTTLS -> {
                put("mail.imap.starttls.enable", "true")
                put("mail.imap.starttls.required", "true")
            }
            MailSecurity.NONE -> Unit
        }
    }

    /**
     * Turns a mail-library failure into something worth showing a person.
     *
     * The distinction that matters is the first one: a refused sign-in and an
     * unreachable server look identical in a stack trace and mean entirely
     * different things to fix. Everything else falls through to the server's own
     * words, which are usually better than any paraphrase — `MessagingException`
     * carries what the server actually said.
     */
    fun explain(failure: Throwable): String = when {
        failure is AuthenticationFailedException ->
            "the server refused this username and password. Most providers need an " +
                "app password here rather than your normal one."
        failure is MessagingException && failure.nextException is UnknownHostException ->
            "that server name could not be found. Check it for typos."
        failure is MessagingException ->
            failure.message?.trim()?.ifEmpty { null } ?: "the server refused the connection."
        else -> failure.message?.trim()?.ifEmpty { null } ?: "the connection failed."
    }

    private const val SMTP = "smtp"
    private const val IMAP = "imap"
    private const val CHARSET = "UTF-8"
    private const val INBOX = "INBOX"

    /**
     * Where a deleted message goes, in the order servers name it. Gmail's is
     * bracketed and its own; the plain ones cover Dovecot, Fastmail and iCloud.
     */
    private val TRASH_NAMES = listOf("[Gmail]/Trash", "Trash", "Deleted Messages", "Deleted Items")
}
