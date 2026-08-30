package io.github.m1n1m1.easymatic.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationRefTest {

    @Test
    fun `a conversation spec round-trips`() {
        val spec = ConversationRef.format("com.whatsapp", "0|com.whatsapp|1|null|10123")
        assertEquals(
            ConversationRef.Parsed("com.whatsapp", "0|com.whatsapp|1|null|10123"),
            ConversationRef.parse(spec),
        )
    }

    /**
     * The encoding decision, pinned. A notification key contains the separator as a
     * matter of course — the platform formats it that way — which is exactly why it
     * is last and unsplit.
     */
    @Test
    fun `a key made almost entirely of separators survives`() {
        val key = "0|org.thoughtcrime.securesms|1|null|10250|tag|with|pipes"
        assertEquals(
            ConversationRef.Parsed("org.thoughtcrime.securesms", key),
            ConversationRef.parse(ConversationRef.format("org.thoughtcrime.securesms", key)),
        )
    }

    @Test
    fun `nothing wired in is null rather than a default`() {
        assertNull(ConversationRef.parse(""))
        assertNull(ConversationRef.parse("   "))
    }

    /** A truncated reference replies to nobody rather than to whatever it can salvage. */
    @Test
    fun `a malformed spec is null`() {
        assertNull(ConversationRef.parse("msg:"))
        assertNull(ConversationRef.parse("msg:com.whatsapp"))
        assertNull(ConversationRef.parse("msg:|0|com.whatsapp|1"))
        assertNull(ConversationRef.parse("msg:com.whatsapp|"))
    }

    /**
     * Text that arrived on a wire from somewhere else — a variable, a text
     * transform — is not a reference, and must not be treated as one.
     */
    @Test
    fun `text that is not a reference is null`() {
        assertNull(ConversationRef.parse("com.whatsapp|0|com.whatsapp|1"))
        assertNull(ConversationRef.parse("mail:abc|1|2|INBOX"))
        assertNull(ConversationRef.parse("Anna: are you coming?"))
    }
}
