package com.example.ottomatic.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether a macro runs, so every case here is a bug somebody
 * would otherwise meet as "my phone went off forty times".
 */
class MailDedupTest {

    @Test
    fun `the first ever check reports nothing and marks where it started`() {
        val decision = MailDedup.decide(stored = null, reportedValidity = 7, uids = listOf(3, 4, 5))

        // Arming a mail trigger on a full inbox must not fire once per message
        // already in it. History is not an arrival.
        assertTrue(decision is MailDedup.Decision.Rebaseline)
        assertEquals(MailBaseline(7, 5), (decision as MailDedup.Decision.Rebaseline).next)
        assertNull(decision.reason)
    }

    @Test
    fun `an empty mailbox baselines at zero`() {
        val decision = MailDedup.decide(stored = null, reportedValidity = 7, uids = emptyList())

        assertEquals(MailBaseline(7, 0), (decision as MailDedup.Decision.Rebaseline).next)
    }

    @Test
    fun `messages above the mark are reported in order`() {
        val decision = MailDedup.decide(MailBaseline(7, 5), reportedValidity = 7, uids = listOf(8, 6, 7))

        val report = decision as MailDedup.Decision.Report
        assertEquals(listOf(6L, 7L, 8L), report.uids)
        assertEquals(MailBaseline(7, 8), report.next)
    }

    @Test
    fun `a message at the mark is not reported twice`() {
        val decision = MailDedup.decide(MailBaseline(7, 5), reportedValidity = 7, uids = listOf(5))

        assertEquals(emptyList<Long>(), (decision as MailDedup.Decision.Report).uids)
    }

    /**
     * The case that would be catastrophic if it reset the mark to zero: the server
     * renumbered the mailbox, so every stored uid is meaningless — and replaying
     * from the start would run the macro once per message in the whole folder.
     */
    @Test
    fun `a renumbered mailbox re-syncs silently instead of replaying`() {
        val decision = MailDedup.decide(MailBaseline(7, 5), reportedValidity = 99, uids = listOf(1, 2, 3))

        val rebaseline = decision as MailDedup.Decision.Rebaseline
        assertEquals(MailBaseline(99, 3), rebaseline.next)
        // Silent about the mail, but not about itself: a trigger that skips a round
        // owes the user an explanation.
        assertNotNull(rebaseline.reason)
    }

    /** A round that found nothing must not walk the mark backwards. */
    @Test
    fun `an empty result leaves the mark where it was`() {
        val decision = MailDedup.decide(MailBaseline(7, 5), reportedValidity = 7, uids = emptyList())

        val report = decision as MailDedup.Decision.Report
        assertTrue(report.uids.isEmpty())
        assertEquals(MailBaseline(7, 5), report.next)
    }
}
