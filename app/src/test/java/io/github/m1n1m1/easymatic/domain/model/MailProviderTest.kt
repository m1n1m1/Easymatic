package io.github.m1n1m1.easymatic.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MailProviderTest {

    /**
     * A preset exists to spare somebody a hostname lookup, so a blank one is worse
     * than no preset at all: it fills the form with nothing and looks like it
     * worked. [MailProvider.CUSTOM] and [MailProvider.MICROSOFT] are the two that
     * are meant to be empty — one asks the user, the other refuses.
     */
    @Test
    fun `every usable preset can actually be connected to`() {
        val fillable = MailProvider.entries - MailProvider.CUSTOM - MailProvider.MICROSOFT
        fillable.forEach { provider ->
            assertTrue("${provider.name} has no SMTP host", provider.smtpHost.isNotBlank())
            assertTrue("${provider.name} has no IMAP host", provider.imapHost.isNotBlank())
            assertTrue("${provider.name} has no SMTP port", provider.smtpPort > 0)
            assertTrue("${provider.name} has no IMAP port", provider.imapPort > 0)
            assertTrue("${provider.name} is unencrypted", provider.smtpSecurity != MailSecurity.NONE)
            assertTrue("${provider.name} is unencrypted", provider.imapSecurity != MailSecurity.NONE)
        }
    }

    /**
     * Every one of these needs an app password rather than the account password,
     * and that note is the only place the user is told so. Losing it turns the
     * commonest setup failure back into an unexplained `AUTHENTICATE failed`.
     */
    @Test
    fun `every preset that needs an app password says where to make one`() {
        val withPages = MailProvider.entries.filter { it.appPasswordUrl.isNotBlank() }
        assertTrue(withPages.isNotEmpty())
        withPages.forEach { provider ->
            assertTrue("${provider.name} links a page but explains nothing", provider.note.isNotBlank())
            // A bare host: the scheme is added at the point of use, so one here
            // would produce `https://https://…`.
            assertFalse("${provider.name} carries a scheme", provider.appPasswordUrl.contains("://"))
        }
    }

    @Test
    fun `a known domain picks its provider`() {
        assertEquals(MailProvider.GMAIL, MailProvider.forAddress("someone@gmail.com"))
        assertEquals(MailProvider.ICLOUD, MailProvider.forAddress("someone@ME.COM"))
        assertEquals(MailProvider.FASTMAIL, MailProvider.forAddress("someone@fastmail.com"))
    }

    @Test
    fun `an unknown or half-typed address falls back to custom`() {
        assertEquals(MailProvider.CUSTOM, MailProvider.forAddress("someone@my-own-server.net"))
        assertEquals(MailProvider.CUSTOM, MailProvider.forAddress("someone"))
        assertEquals(MailProvider.CUSTOM, MailProvider.forAddress(""))
    }

    /**
     * The one case where guessing right and then refusing is the point. Answering
     * CUSTOM here would hand the user an empty form for an account that cannot be
     * made to work whatever they type into it.
     */
    @Test
    fun `a microsoft address is recognised so it can be refused`() {
        assertEquals(MailProvider.MICROSOFT, MailProvider.forAddress("someone@outlook.com"))
        assertEquals(MailProvider.MICROSOFT, MailProvider.forAddress("someone@hotmail.com"))
        assertFalse(MailProvider.MICROSOFT.usable)
        assertTrue(MailProvider.MICROSOFT.note.isNotBlank())
    }

    @Test
    fun `applying a preset fills the servers and records which one it was`() {
        val account = MailAccount(id = "1", name = "Work", address = "me@gmail.com")

        val filled = MailProvider.GMAIL.applyTo(account)

        assertEquals("smtp.gmail.com", filled.smtpHost)
        assertEquals("imap.gmail.com", filled.imapHost)
        assertEquals(MailProvider.GMAIL.name, filled.preset)
        assertEquals(MailProvider.GMAIL, MailProvider.byName(filled.preset))
        // Identity is untouched — a preset knows about servers, not about people.
        assertEquals("Work", filled.name)
        assertEquals("me@gmail.com", filled.address)
    }

    /** The editor's dropdown renders these; two rows reading the same is a bug. */
    @Test
    fun `labels are distinct and non-blank`() {
        val labels = MailProvider.entries.map { it.label }
        assertTrue(labels.none { it.isBlank() })
        assertEquals(labels.size, labels.distinct().size)
    }

    /** "Other" belongs at the bottom of a list of named things. */
    @Test
    fun `custom is last`() {
        assertEquals(MailProvider.CUSTOM, MailProvider.entries.last())
    }
}
