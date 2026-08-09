package com.example.ottomatic.engine.action

import com.example.ottomatic.engine.DefaultExecutionContext
import com.example.ottomatic.engine.ExecutionRoute
import com.example.ottomatic.engine.FakeMail
import com.example.ottomatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `action.send_mail` follows `action.http`'s contract rather than inventing one: a
 * failure is a value on the `state` port, not an exception, and `out` still pulses
 * — nothing here halts a macro.
 */
class SendMailActionTest {

    private val mail = FakeMail()
    private val context = DefaultExecutionContext(
        systemServices = RecordingSystemServices(),
        mail = mail,
    )
    private val action = SendMailAction()

    private fun config(
        accountId: String = "acc-1",
        to: String = "someone@example.com",
    ) = SendMailConfig(accountId = accountId, to = to, subject = "Hello", body = "Body")

    @Test
    fun `a configured send reaches the facade and reports sent`() = runBlocking {
        val out = action.execute(config(), context)

        assertEquals(1, mail.sent.size)
        assertEquals("someone@example.com", mail.sent.single().to)
        assertTrue(out.value.sent)
        assertEquals("", out.value.error)
    }

    @Test
    fun `an unchosen account never reaches the facade`() = runBlocking {
        val out = action.execute(config(accountId = ""), context)

        assertTrue(mail.sent.isEmpty())
        assertFalse(out.value.sent)
        assertEquals("No mail account chosen", out.value.error)
    }

    @Test
    fun `an empty recipient never reaches the facade`() = runBlocking {
        val out = action.execute(config(to = ""), context)

        assertTrue(mail.sent.isEmpty())
        assertFalse(out.value.sent)
        assertEquals("No recipient", out.value.error)
    }

    /**
     * The whole reason `MailSent` carries an `error` where `SmsSent` does not: a
     * mail send fails for a dozen reasons and "which one" is the entire question
     * when a macro stops working.
     */
    @Test
    fun `a refused send reports the reason rather than throwing`() = runBlocking {
        mail.sendFailure = "the server refused this username and password"

        val out = action.execute(config(), context)

        assertFalse(out.value.sent)
        assertEquals("the server refused this username and password", out.value.error)
    }

    @Test
    fun `a failed send still pulses out`() = runBlocking {
        mail.sendFailure = "no network"

        val out = action.execute(config(), context)

        assertEquals(ExecutionRoute.OUT, out.route)
        assertFalse(out.halt)
    }
}
