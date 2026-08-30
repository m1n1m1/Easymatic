package io.github.m1n1m1.easymatic.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MailRefTest {

    @Test
    fun `a ref round-trips`() {
        val spec = MailRef.format("acc-1", "INBOX", uidValidity = 12345, uid = 987)

        val parsed = MailRef.parse(spec)

        assertEquals("acc-1", parsed?.accountId)
        assertEquals("INBOX", parsed?.folder)
        assertEquals(12345L, parsed?.uidValidity)
        assertEquals(987L, parsed?.uid)
    }

    /**
     * The reason the folder is last and the split is limited. Gmail's own folders
     * are `[Gmail]/All Mail`; a naive split would take "All Mail" for a fifth field
     * and refuse a perfectly ordinary mailbox.
     */
    @Test
    fun `a folder containing a slash survives`() {
        val spec = MailRef.format("acc-1", "[Gmail]/All Mail", 1, 2)

        assertEquals("[Gmail]/All Mail", MailRef.parse(spec)?.folder)
    }

    /** And one containing the separator itself, which IMAP permits. */
    @Test
    fun `a folder containing the separator survives`() {
        val spec = MailRef.format("acc-1", "Work|Urgent", 1, 2)

        assertEquals("Work|Urgent", MailRef.parse(spec)?.folder)
    }

    /**
     * Fails closed, for `PhoneRef`'s reason: a half-parsed reference would act on
     * *some* message, and acting on the wrong message is worse than reporting that
     * there was nothing to act on.
     */
    @Test
    fun `anything malformed is null rather than a guess`() {
        assertNull(MailRef.parse(""))
        assertNull(MailRef.parse("acc-1|1|2|INBOX"))
        assertNull(MailRef.parse("mail:acc-1|1|2"))
        assertNull(MailRef.parse("mail:acc-1|notanumber|2|INBOX"))
        assertNull(MailRef.parse("mail:acc-1|1|notanumber|INBOX"))
        assertNull(MailRef.parse("mail:|1|2|INBOX"))
        assertNull(MailRef.parse("mail:acc-1|1|2|"))
        assertNull(MailRef.parse("hello"))
    }
}
