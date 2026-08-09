package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.MailOp
import com.example.ottomatic.domain.model.MailRef
import com.example.ottomatic.engine.DefaultExecutionContext
import com.example.ottomatic.engine.ExecutionRoute
import com.example.ottomatic.engine.FakeMail
import com.example.ottomatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MailUpdateActionTest {

    private val mail = FakeMail()
    private val context = DefaultExecutionContext(
        systemServices = RecordingSystemServices(),
        mail = mail,
    )
    private val action = MailUpdateAction()

    private val ref = MailRef.format("acc-1", "INBOX", uidValidity = 9, uid = 42)

    @Test
    fun `a valid reference is unpacked and passed on`() = runBlocking {
        val out = action.execute(MailUpdateConfig(ref = ref, op = MailOp.MARK_READ), context)

        val request = mail.updated.single()
        assertEquals("acc-1", request.accountId)
        assertEquals("INBOX", request.folder)
        assertEquals(42L, request.uid)
        assertEquals(9L, request.uidValidity)
        assertEquals(MailOp.MARK_READ, request.op)
        assertTrue(out.value.changed)
    }

    /**
     * Fails closed rather than acting on some other message — the whole reason
     * `MailRef.parse` returns null instead of guessing.
     */
    @Test
    fun `an unparseable reference never reaches the facade`() = runBlocking {
        val out = action.execute(MailUpdateConfig(ref = "not-a-ref"), context)

        assertTrue(mail.updated.isEmpty())
        assertFalse(out.value.changed)
        assertTrue(out.value.error, out.value.error.contains("not-a-ref"))
    }

    @Test
    fun `an unwired reference says so plainly`() = runBlocking {
        val out = action.execute(MailUpdateConfig(ref = ""), context)

        assertTrue(mail.updated.isEmpty())
        assertEquals("No message wired in", out.value.error)
    }

    /** Moving to "" would put the message somewhere nobody asked for. */
    @Test
    fun `a move with no destination is refused`() = runBlocking {
        val out = action.execute(MailUpdateConfig(ref = ref, op = MailOp.MOVE), context)

        assertTrue(mail.updated.isEmpty())
        assertFalse(out.value.changed)
        assertTrue(out.value.error, out.value.error.contains("nowhere to move"))
    }

    @Test
    fun `a move with a destination goes through`() = runBlocking {
        action.execute(MailUpdateConfig(ref = ref, op = MailOp.MOVE, targetFolder = "Archive"), context)

        assertEquals("Archive", mail.updated.single().targetFolder)
    }

    /** A message that has since moved is not a reason to stop the macro. */
    @Test
    fun `a refused update reports and still pulses out`() = runBlocking {
        mail.updateFailure = "That message is no longer in this mailbox"

        val out = action.execute(MailUpdateConfig(ref = ref), context)

        assertFalse(out.value.changed)
        assertEquals("That message is no longer in this mailbox", out.value.error)
        assertEquals(ExecutionRoute.OUT, out.route)
        assertFalse(out.halt)
    }
}
