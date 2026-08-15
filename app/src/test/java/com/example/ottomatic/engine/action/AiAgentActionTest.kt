package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.AiModel
import com.example.ottomatic.core.service.AiReply
import com.example.ottomatic.core.service.LogEntry
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.engine.DefaultExecutionContext
import com.example.ottomatic.engine.ExecutionRoute
import com.example.ottomatic.engine.FakeAi
import com.example.ottomatic.engine.FakeTurn
import com.example.ottomatic.engine.RecordingSystemServices
import com.example.ottomatic.engine.ai.AiToolDepth
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `action.ai_agent` — what the model is offered, what it is allowed to do with it,
 * and what the node does when any of it goes wrong.
 *
 * [AiPromptActionTest]'s shape and its stance on failure: a model that could not be
 * reached, a key that was refused and a turn cap reached all land on the fallback and
 * still pulse `out`, because halting a macro from inside a text field is invisible
 * where an `action.if` over the output is not.
 */
class AiAgentActionTest {

    private val logs = mutableListOf<LogEntry>()
    private val ai = FakeAi()
    private val services = RecordingSystemServices()
    private val context = DefaultExecutionContext(
        systemServices = services,
        ai = ai,
        logger = { logs += it },
    )
    private val action = AiAgentAction()

    @After
    fun tearDown() = AiToolDepth.reset()

    private fun config(tools: String = "", prompt: String = "what is the battery at?") = AiAgentConfig(
        connectionId = CONNECTION,
        prompt = prompt,
        tools = tools,
    )

    // ---- what reaches the facade ------------------------------------------------

    @Test
    fun `the answer lands on the answer port`() = runBlocking {
        ai.reply = AiReply(text = "72%")
        val out = action.execute(config(), context)
        assertEquals("72%", out.value)
        assertEquals(ExecutionRoute.OUT, out.route)
    }

    @Test
    fun `the configured tools are the tools the model is offered`() = runBlocking {
        action.execute(config(tools = "value.battery\naction.notify"), context)
        assertEquals(listOf("value_battery", "action_notify"), ai.offered.single().map { it.name })
    }

    /**
     * A tool list the author emptied is a node that asks a question, which is a
     * legitimate thing to have built — so it delegates rather than failing.
     */
    @Test
    fun `an empty tool list is still a question`() = runBlocking {
        ai.reply = AiReply(text = "I cannot check that")
        val out = action.execute(config(tools = "   "), context)
        assertEquals("I cannot check that", out.value)
        assertEquals(emptyList<Any>(), ai.offered.single())
    }

    /**
     * The default is BALANCED rather than FAST, unlike every other AI node here: the
     * fast tiers are the small models, and choosing among a dozen tools over several
     * turns is what they are worst at.
     */
    @Test
    fun `the node defaults to the balanced tier`() {
        assertEquals(AiModel.BALANCED, AiAgentConfig().model)
    }

    @Test
    fun `the turn limit reaches the facade`() = runBlocking {
        action.execute(config(tools = "value.battery").copy(maxTurns = 3), context)
        // A tool call is only reachable through `converse`, so a recorded request
        // with tools offered is proof the loop path was taken rather than `complete`.
        assertEquals(1, ai.offered.size)
    }

    // ---- the tools actually run -------------------------------------------------

    @Test
    fun `a tool the model calls is run against the app`() = runBlocking {
        ai.turns += FakeTurn.call("action_notify", mapOf("text" to "the battery is low"))
        ai.turns += FakeTurn.says("told you")
        val out = action.execute(config(tools = "action.notify"), context)

        assertEquals("told you", out.value)
        assertEquals("the battery is low", services.notifications.single().second)
        assertEquals("Done", ai.results.single().text)
        assertFalse(ai.results.single().isError)
    }

    /** A tool that fails is reported to the model, not out of the node. */
    @Test
    fun `a tool the model invents is an error it can recover from`() = runBlocking {
        ai.turns += FakeTurn.call("no_such_tool")
        ai.turns += FakeTurn.says("never mind")
        val out = action.execute(config(tools = "value.battery"), context)

        assertEquals("never mind", out.value)
        assertTrue(ai.results.single().isError)
    }

    // ---- failure is never fatal -------------------------------------------------

    @Test
    fun `a failure lands on the fallback and still pulses out`() = runBlocking {
        ai.reply = AiReply(error = "API key not valid")
        val out = action.execute(config().copy(fallback = "could not ask"), context)
        assertEquals("could not ask", out.value)
        assertEquals(ExecutionRoute.OUT, out.route)
        assertFalse(out.halt)
    }

    @Test
    fun `the reason a run failed is named in the console`() = runBlocking {
        ai.reply = AiReply(error = "API key not valid")
        action.execute(config(), context)
        assertTrue(
            logs.any { it.level == LogLevel.ERROR && it.message.contains("API key not valid") },
        )
    }

    @Test
    fun `nothing is asked when there is nothing to do`() = runBlocking {
        val out = action.execute(config(prompt = "  ").copy(fallback = "idle"), context)
        assertEquals("idle", out.value)
        assertEquals(emptyList<Any>(), ai.requests)
    }

    @Test
    fun `a truncated answer is delivered and warned about`() = runBlocking {
        ai.reply = AiReply(text = "half an ans", truncated = true)
        val out = action.execute(config(), context)
        assertEquals("half an ans", out.value)
        assertTrue(logs.any { it.level == LogLevel.WARN && it.message.contains("cut off") })
    }

    private companion object {
        const val CONNECTION = "connection-id"
    }
}
