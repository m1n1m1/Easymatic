package com.example.ottomatic.data.mail

import com.example.ottomatic.core.service.MailLimits
import com.example.ottomatic.domain.model.MailAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one number in the mail feature that looks like a copy-paste slip and is not.
 *
 * `mail.imap.timeout` is the **socket read timeout**, and `IMAPFolder.idle()`
 * spends its entire life blocked on a socket read waiting for the server to speak.
 * A quiet mailbox says nothing for hours, so the ordinary 30-second timeout does
 * not protect an IDLE connection — it kills a perfectly healthy one every thirty
 * seconds. The watcher then backs off, gives up on push after three "failures",
 * and the whole feature degrades to the poll while the server was fine throughout.
 *
 * This test exists because the fix looks exactly like an inconsistency somebody
 * would helpfully remove.
 */
class MailIdleTimeoutTest {

    private val account = MailAccount(
        id = "acc-1",
        name = "Work",
        address = "me@example.com",
        imapHost = "imap.example.com",
    )

    @Test
    fun `an ordinary read uses the short timeout`() {
        val properties = MailTransport.imapProperties(account)

        assertEquals(MailLimits.READ_TIMEOUT_MS.toString(), properties["mail.imap.timeout"])
    }

    @Test
    fun `an idle connection outlives its own re-issue interval`() {
        val properties = MailTransport.imapProperties(
            account,
            readTimeoutMs = MailIdleWatcher.IDLE_SOCKET_TIMEOUT_MS,
        )

        val timeout = properties["mail.imap.timeout"].toString().toLong()
        assertTrue(
            "an IDLE socket must not time out before its own NOOP re-issue lands",
            timeout > MailIdleWatcher.IDLE_REISSUE_MS,
        )
    }

    /** Still finite, so a genuinely wedged socket errors rather than hanging a thread. */
    @Test
    fun `the idle timeout is bounded`() {
        assertTrue(MailIdleWatcher.IDLE_SOCKET_TIMEOUT_MS > 0)
    }
}
