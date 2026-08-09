package com.example.ottomatic.data.mail

import com.example.ottomatic.core.service.MailLimits
import com.example.ottomatic.domain.model.MailAccount
import com.example.ottomatic.domain.model.MailSecurity
import java.net.UnknownHostException
import java.util.Properties
import javax.mail.AuthenticationFailedException
import javax.mail.MessagingException
import javax.mail.Session

/**
 * The one place in the app that speaks SMTP and IMAP — and therefore the only
 * file that imports `javax.mail`.
 *
 * **Blocking**, deliberately: callers wrap it. Four of them will (`AndroidMail`,
 * the poll worker, the IDLE watcher and the account editor's connection test), and
 * a suspending transport would have each of them believing the dispatcher was
 * somebody else's problem. The suspending boundary is [com.example.ottomatic.core.service.Mail],
 * one layer up, which is where it belongs.
 *
 * One transport shared by everything for the reason
 * [com.example.ottomatic.domain.model.TimeOfDay] is shared by the schedule trigger
 * and its picker: two readings of one message would eventually disagree, and the
 * one that disagreed would be the one nobody was looking at.
 */
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

    /** Properties for an incoming connection. See [smtpProperties] on the timeouts. */
    fun imapProperties(account: MailAccount): Properties = Properties().apply {
        put("mail.imap.host", account.imapHost)
        put("mail.imap.port", account.imapPort.toString())
        put("mail.imap.connectiontimeout", MailLimits.CONNECT_TIMEOUT_MS.toString())
        put("mail.imap.timeout", MailLimits.READ_TIMEOUT_MS.toString())
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
}
