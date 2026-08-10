package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.AiModel
import com.example.ottomatic.core.service.AiReply
import com.example.ottomatic.core.service.LogEntry
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.engine.DefaultExecutionContext
import com.example.ottomatic.engine.ExecutionRoute
import com.example.ottomatic.engine.FakeAi
import com.example.ottomatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `action.ai_prompt` — what reaches the port, and what the console is told.
 *
 * The node's whole job beyond forwarding the prompt is deciding what a failure
 * costs, and the answer is `action.script`'s: it lands on the fallback and pulses
 * `out`, because halting a macro from inside a text field is invisible where an
 * `action.if` over the output is not.
 */
class AiPromptActionTest {

    private val logs = mutableListOf<LogEntry>()
    private val ai = FakeAi()
    private val context = DefaultExecutionContext(
        systemServices = RecordingSystemServices(),
        ai = ai,
        logger = { logs += it },
    )
    private val action = AiPromptAction()

    @Test
    fun `the reply lands on the answer port`() = runBlocking {
        ai.reply = AiReply(text = "42")
        val out = action.execute(AiPromptConfig(connectionId = CONNECTION, prompt = "six times seven"), context)
        assertEquals("42", out.value)
    }

    /**
     * Which connection a prompt is billed to is a per-node decision — quota is per
     * key — so it has to arrive at the facade rather than being resolved from some
     * implicit default. A node that silently used whichever connection sorted first
     * would spend a key the user did not choose.
     */
    @Test
    fun `the chosen connection reaches the facade`() = runBlocking {
        action.execute(AiPromptConfig(connectionId = "work-key", prompt = "hi"), context)
        assertEquals("work-key", ai.requests.single().connectionId)
    }

    @Test
    fun `the prompt, instruction, model and limit all reach the facade`() = runBlocking {
        action.execute(
            AiPromptConfig(
                connectionId = CONNECTION,
                prompt = "summarise this",
                systemInstruction = "Answer in one sentence",
                model = AiModel.THOROUGH,
                maxOutputTokens = 77,
            ),
            context,
        )
        val request = ai.requests.single()
        assertEquals("summarise this", request.prompt)
        assertEquals("Answer in one sentence", request.systemInstruction)
        assertEquals(AiModel.THOROUGH, request.model)
        assertEquals(77, request.maxOutputTokens)
    }

    @Test
    fun `a failure lands on the fallback and still pulses out`() = runBlocking {
        ai.reply = AiReply(error = "API key not valid.")
        val out = action.execute(
            AiPromptConfig(connectionId = CONNECTION, prompt = "anything", fallback = "unknown"),
            context,
        )
        assertEquals("unknown", out.value)
        // Nothing halted: acting on a failure is an `action.if` over this port.
        assertFalse(out.halt)
        assertEquals(ExecutionRoute.OUT, out.route)
    }

    @Test
    fun `a failure names the reason in the console rather than only that it failed`() = runBlocking {
        ai.reply = AiReply(error = "API key not valid.")
        action.execute(AiPromptConfig(connectionId = CONNECTION, prompt = "anything"), context)
        val error = logs.single { it.level == LogLevel.ERROR }
        assertTrue(error.message.contains("API key not valid."))
    }

    @Test
    fun `an unset fallback means the port carries empty text rather than the error`() = runBlocking {
        ai.reply = AiReply(error = "Quota exceeded")
        val out = action.execute(AiPromptConfig(connectionId = CONNECTION, prompt = "anything"), context)
        assertEquals("", out.value)
    }

    /**
     * The one case that is neither success nor failure: real output that stops
     * mid-sentence. It reaches the port — a macro would send it either way — and
     * the console says it was cut off, which is the only thing that distinguishes
     * it from the model's own idea of a complete answer.
     */
    @Test
    fun `a truncated reply is delivered and warned about`() = runBlocking {
        ai.reply = AiReply(text = "It was the best of", truncated = true)
        val out = action.execute(AiPromptConfig(connectionId = CONNECTION, prompt = "tell me a story"), context)
        assertEquals("It was the best of", out.value)
        assertTrue(logs.any { it.level == LogLevel.WARN && it.message.contains("cut off") })
    }

    @Test
    fun `a blank prompt is never sent and never bills for itself`() = runBlocking {
        val out = action.execute(
            AiPromptConfig(connectionId = CONNECTION, prompt = "", fallback = "nothing asked"),
            context,
        )
        assertTrue(ai.requests.isEmpty())
        assertEquals("nothing asked", out.value)
        assertTrue(logs.any { it.level == LogLevel.ERROR })
    }

    @Test
    fun `a phone with no connection set up degrades to the fallback like any other failure`() = runBlocking {
        // The default context has no AI at all, which is what `NoAi` models.
        val bare = DefaultExecutionContext(
            systemServices = RecordingSystemServices(),
            logger = { logs += it },
        )
        val out = action.execute(
            AiPromptConfig(connectionId = CONNECTION, prompt = "hello", fallback = "offline"),
            bare,
        )
        assertEquals("offline", out.value)
        assertTrue(logs.any { it.level == LogLevel.ERROR && it.message.contains("no AI connection", true) })
    }

    private companion object {
        const val CONNECTION = "connection-id"
    }
}
