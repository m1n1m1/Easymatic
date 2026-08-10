package com.example.ottomatic.data.ai

import com.example.ottomatic.core.service.AiModel
import com.example.ottomatic.core.service.AiRequest
import com.example.ottomatic.data.AiConnectionRepository
import com.example.ottomatic.data.security.FakeSecrets
import com.example.ottomatic.domain.model.AiProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The half of the routing facade that can be tested without a network: the guards
 * that must never reach one, and how the two standing instructions are combined.
 *
 * Everything past those guards is a live HTTP call, which is exactly why the
 * protocols are pure objects tested separately — the split is what makes this file
 * short rather than impossible.
 */
class RoutingAiTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val secrets = FakeSecrets()

    private fun repository() = AiConnectionRepository(folder.root, secrets)

    private fun request(connectionId: String, prompt: String = "hi") =
        AiRequest(connectionId = connectionId, prompt = prompt)

    // ---- combining the two standing instructions -------------------------------

    /**
     * The order is the design decision, not an implementation detail: the
     * connection's rules are the frame and the node's task arrives inside it.
     */
    @Test
    fun `both instructions are sent, the connection's first`() {
        assertEquals(
            "Answer in German.\n\nReply with one word.",
            combineInstructions("Answer in German.", "Reply with one word."),
        )
    }

    /**
     * The case that makes the change invisible to every macro written before it: a
     * connection with no standing instruction behaves exactly as it always did.
     */
    @Test
    fun `the node's instruction alone passes through untouched`() {
        assertEquals("Reply with one word.", combineInstructions("", "Reply with one word."))
    }

    @Test
    fun `the connection's instruction alone passes through untouched`() {
        assertEquals("Answer in German.", combineInstructions("Answer in German.", ""))
    }

    /** Both blank has to stay blank, because every protocol omits the field entirely then. */
    @Test
    fun `both blank combines to nothing rather than to whitespace`() {
        assertEquals("", combineInstructions("", ""))
        assertEquals("", combineInstructions("  ", "\n"))
    }

    @Test
    fun `a stray blank line either side does not become three`() {
        assertEquals("A\n\nB", combineInstructions("  A\n", "\n B  "))
    }

    // ---- routing ---------------------------------------------------------------

    @Test
    fun `every provider routes to a protocol and no two share one`() {
        val protocols = AiProvider.entries.map { protocolFor(it) }
        assertEquals(AiProvider.entries.size, protocols.size)
        // Gemini and Anthropic have their own; the three OpenAI-shaped ones share a
        // class but must not share an instance, or they would share a base URL.
        assertEquals(AiProvider.entries.size, protocols.distinct().size)
    }

    @Test
    fun `the three OpenAI-shaped providers keep their own endpoints`() {
        val openAi = protocolFor(AiProvider.OPENAI)
        val openRouter = protocolFor(AiProvider.OPENROUTER)
        val blank = com.example.ottomatic.domain.model.AiConnection(id = "x", name = "x")
        assertNotEquals(
            openAi.endpoint(blank, AiModel.FAST),
            openRouter.endpoint(blank, AiModel.FAST),
        )
    }

    // ---- the guards that never reach the network -------------------------------

    @Test
    fun `a blank prompt is refused without a connection being looked up`() = runBlocking {
        val reply = RoutingAi(repository()).complete(request(connectionId = "anything", prompt = "  "))
        assertTrue(reply.error.contains("No prompt"))
    }

    @Test
    fun `a node with no connection chosen says so rather than picking one`() = runBlocking {
        val reply = RoutingAi(repository()).complete(request(connectionId = ""))
        assertTrue(reply.error.contains("No AI connection chosen"))
    }

    /**
     * The two messages must differ, because the fixes do: re-pick the connection
     * versus paste the key in again.
     */
    @Test
    fun `a deleted connection and an unreadable key are reported differently`() = runBlocking {
        val repository = repository()
        val ai = RoutingAi(repository)
        val deleted = ai.complete(request(connectionId = "never-existed")).error

        val created = repository.create("Personal", AiProvider.GEMINI)
        val noKey = ai.complete(request(connectionId = created.id)).error

        assertTrue(deleted.contains("no longer exists"))
        assertTrue(noKey.contains("Personal"))
        assertTrue(noKey.contains("paste"))
        assertNotEquals(deleted, noKey)
    }

    /**
     * Without this the request reaches the transport as a malformed URL, comes back
     * as `-1`, and is worded "check the phone's connection" — sending somebody to
     * look at their Wi-Fi over an empty field in this app.
     */
    @Test
    fun `a self-hosted connection with no address never reaches the network`() = runBlocking {
        val repository = repository()
        val created = repository.create("Local", AiProvider.OPENAI_COMPATIBLE)
        repository.setKey(created.id, "anything")
        val reply = RoutingAi(repository).complete(request(connectionId = created.id))
        assertTrue(reply.error.contains("server address"))
        assertFalse(reply.error.contains("connection"))
    }
}
