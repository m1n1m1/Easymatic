package com.example.ottomatic.data.mail

import com.example.ottomatic.domain.model.MailAccount
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPStore
import java.util.concurrent.atomic.AtomicBoolean
import javax.mail.Folder
import javax.mail.Session
import javax.mail.event.MessageCountAdapter
import javax.mail.event.MessageCountEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One held-open IMAP connection, parked in IDLE, for one mailbox.
 *
 * This is how every third-party mail client gets near-instant delivery — K-9,
 * Thunderbird and FairEmail all do exactly this. Gmail and Outlook cheat: their
 * servers push to their own apps over FCM, which is a channel no third party can
 * use for an arbitrary account.
 *
 * **There is no wake lock here, deliberately.** A partial wake lock held for an
 * idle socket is the battery drain the whole feature would be blamed for, and it
 * buys nothing: data arriving on a socket wakes the app anyway. What actually
 * kills IDLE is **doze**, which suspends the app's network outright, and no wake
 * lock this app can hold changes that. The two real answers are already in place —
 * the battery-optimisation exemption on the Permissions screen, and
 * `MacroEngineService`'s foreground notification — plus the WorkManager poll
 * underneath, which is what covers the hours the radio is off.
 *
 * [IDLE_REISSUE_MS] is the fiddliest part. `IMAPFolder.idle()` **blocks** and
 * cannot be cancelled, so the re-issue is done by a watchdog coroutine that calls
 * `getMessageCount()` on the same folder: that issues a NOOP down the connection,
 * which is JavaMail's documented way to break an IDLE from outside. Cancelling the
 * job alone would leave a thread parked in `idle()` for as long as the server felt
 * like saying nothing — which is why [disconnect] closes the store instead, and
 * closing the socket is what makes the blocked call unwind.
 */
internal class MailIdleWatcher(
    private val account: MailAccount,
    private val password: String,
    private val folder: String,
) {

    @Volatile
    private var store: IMAPStore? = null

    private val arrived = AtomicBoolean(false)

    /**
     * Holds the connection open, calling [onArrival] whenever the server announces
     * new mail, until the coroutine is cancelled or the connection fails.
     *
     * Returns normally — rather than throwing — only when this server has no IDLE
     * to offer, which [onUnsupported] reports. Everything else propagates, because
     * the caller's job is to decide whether it is worth reconnecting.
     */
    suspend fun run(
        scope: CoroutineScope,
        onOpen: () -> Unit,
        onUnsupported: () -> Unit,
        onArrival: suspend () -> Unit,
    ) {
        val opened = connect()
        try {
            if (opened == null) {
                onUnsupported()
                return
            }
            onOpen()
            loop(scope, opened, onArrival)
        } finally {
            disconnect()
        }
    }

    /** The selected folder, or null when the server does not support IDLE at all. */
    private fun connect(): IMAPFolder? {
        val opened = Session.getInstance(MailTransport.imapProperties(account)).getStore(IMAP) as IMAPStore
        store = opened
        opened.connect(account.imapHost, account.imapPort, account.effectiveUsername, password)
        if (!opened.hasCapability(IDLE_CAPABILITY)) return null
        val mailbox = opened.getFolder(folder) as IMAPFolder
        mailbox.open(Folder.READ_ONLY)
        mailbox.addMessageCountListener(
            object : MessageCountAdapter() {
                // Fires on the connection's own thread, so nothing is done here
                // beyond setting a flag the loop below reads.
                override fun messagesAdded(event: MessageCountEvent) {
                    arrived.set(true)
                }
            },
        )
        return mailbox
    }

    private suspend fun loop(scope: CoroutineScope, mailbox: IMAPFolder, onArrival: suspend () -> Unit) {
        while (currentCoroutineContext().isActive) {
            arrived.set(false)
            val watchdog = scope.launch {
                delay(IDLE_REISSUE_MS)
                // A NOOP over the same connection, which is what unblocks idle().
                runCatching { mailbox.messageCount }
            }
            try {
                mailbox.idle()
            } finally {
                watchdog.cancel()
            }
            if (arrived.getAndSet(false)) onArrival()
        }
    }

    /**
     * Closes the connection, which is also the only way to unblock a thread parked
     * in `idle()`. Safe to call from another thread, and from a cancellation — it
     * is what makes teardown synchronous.
     */
    fun disconnect() {
        runCatching { store?.close() }
        store = null
    }

    companion object {
        /**
         * RFC 2177 says a client should re-issue IDLE at least every 29 minutes.
         * Carrier NAT tables are frequently shorter-lived than that, so this sits
         * under both.
         */
        const val IDLE_REISSUE_MS = 25L * 60 * 1000

        private const val IMAP = "imap"
        private const val IDLE_CAPABILITY = "IDLE"
    }
}
