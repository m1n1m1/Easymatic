package com.example.ottomatic.data.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageDedupTest {

    private val dedup = MessageDedup()

    @Test
    fun `the first sight of a conversation is new`() {
        assertTrue(dedup.isNew("chat-a", "1|Anna|hi"))
    }

    /**
     * The whole reason this class exists: a messenger updates one notification per
     * chat rather than posting one per message, so the same post arrives again
     * whenever anything about it changes.
     */
    @Test
    fun `the same message posted again is not new`() {
        dedup.isNew("chat-a", "1|Anna|hi")
        assertFalse(dedup.isNew("chat-a", "1|Anna|hi"))
        assertFalse(dedup.isNew("chat-a", "1|Anna|hi"))
    }

    @Test
    fun `a different message in the same conversation is new`() {
        dedup.isNew("chat-a", "1|Anna|hi")
        assertTrue(dedup.isNew("chat-a", "2|Anna|are you coming?"))
    }

    /** The same word twice a minute apart is two messages, told apart by their times. */
    @Test
    fun `the same text sent twice is two messages`() {
        assertTrue(dedup.isNew("chat-a", "1|Anna|ok"))
        assertTrue(dedup.isNew("chat-a", "2|Anna|ok"))
    }

    @Test
    fun `conversations do not shadow each other`() {
        assertTrue(dedup.isNew("chat-a", "1|Anna|hi"))
        assertTrue(dedup.isNew("chat-b", "1|Anna|hi"))
        assertFalse(dedup.isNew("chat-a", "1|Anna|hi"))
    }

    /**
     * A chat read and later re-posted with the same last message — what a messenger
     * does when it re-syncs after being offline — must not be suppressed as a
     * duplicate of something already dealt with.
     */
    @Test
    fun `a forgotten conversation is new again`() {
        dedup.isNew("chat-a", "1|Anna|hi")
        dedup.forget("chat-a")
        assertTrue(dedup.isNew("chat-a", "1|Anna|hi"))
    }

    @Test
    fun `the memory is bounded and evicts the oldest conversation`() {
        val dedup = MessageDedup(capacity = 2)

        dedup.isNew("chat-a", "1")
        dedup.isNew("chat-b", "1")
        dedup.isNew("chat-c", "1")

        assertEquals(2, dedup.size())
        assertTrue("the oldest conversation is the one forgotten", dedup.isNew("chat-a", "1"))
        assertFalse("the newest is still remembered", dedup.isNew("chat-c", "1"))
    }

    /** Seeing a chat again moves it to the back of the queue, so a busy one is not evicted. */
    @Test
    fun `a conversation seen again is not the next one evicted`() {
        val dedup = MessageDedup(capacity = 2)

        dedup.isNew("chat-a", "1")
        dedup.isNew("chat-b", "1")
        dedup.isNew("chat-a", "2")
        dedup.isNew("chat-c", "1")

        assertFalse("the chat that kept receiving is still remembered", dedup.isNew("chat-a", "2"))
        assertTrue("the idle one was evicted", dedup.isNew("chat-b", "1"))
    }
}
