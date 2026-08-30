package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.MailLimits
import io.github.m1n1m1.easymatic.core.service.MailMessageData
import io.github.m1n1m1.easymatic.engine.DefaultExecutionContext
import io.github.m1n1m1.easymatic.engine.ExecutionRoute
import io.github.m1n1m1.easymatic.engine.FakeMail
import io.github.m1n1m1.easymatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FetchMailActionTest {

    private val mail = FakeMail()
    private val context = DefaultExecutionContext(
        systemServices = RecordingSystemServices(),
        mail = mail,
    )
    private val action = FetchMailAction()

    private fun message(uid: Long, subject: String) = MailMessageData(
        uid = uid,
        uidValidity = 9,
        folder = "INBOX",
        accountId = "acc-1",
        from = "someone@example.com",
        subject = subject,
    )

    @Test
    fun `messages come back as items carrying a usable reference`() = runBlocking {
        mail.messages = listOf(message(42, "Invoice"))

        val out = action.execute(FetchMailConfig(accountId = "acc-1"), context)

        val item = out.value.single()
        assertEquals("Invoice", item.subject)
        // The ref is minted here, not by the server, because it is the graph's
        // handle rather than something the mailbox said.
        assertEquals("mail:acc-1|9|42|INBOX", item.ref)
    }

    @Test
    fun `an unchosen account never reaches the facade`() = runBlocking {
        val out = action.execute(FetchMailConfig(accountId = ""), context)

        assertTrue(mail.fetched.isEmpty())
        assertTrue(out.value.isEmpty())
    }

    /** The cap is announced rather than silently applied — MAX_ITERATIONS' stance. */
    @Test
    fun `an over-large limit is clamped`() = runBlocking {
        action.execute(FetchMailConfig(accountId = "acc-1", limit = 5_000), context)

        assertEquals(MailLimits.MAX_FETCH, mail.fetched.single().limit)
    }

    @Test
    fun `the filters are passed to the server`() = runBlocking {
        action.execute(
            FetchMailConfig(
                accountId = "acc-1",
                folder = "Archive",
                fromContains = "billing@",
                subjectContains = "invoice",
                unreadOnly = false,
            ),
            context,
        )

        val request = mail.fetched.single()
        assertEquals("Archive", request.folder)
        assertEquals("billing@", request.fromContains)
        assertEquals("invoice", request.subjectContains)
        assertEquals(false, request.unreadOnly)
    }

    @Test
    fun `a failure is an empty list rather than a halted macro`() = runBlocking {
        mail.fetchFailure = "the server refused this username and password"

        val out = action.execute(FetchMailConfig(accountId = "acc-1"), context)

        assertTrue(out.value.isEmpty())
        assertEquals(ExecutionRoute.OUT, out.route)
        assertEquals(false, out.halt)
    }
}
