package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.AiParamSchema
import com.example.ottomatic.core.service.AiReply
import com.example.ottomatic.core.service.LogEntry
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.SmartHomeTargetKind
import com.example.ottomatic.domain.model.SmartHomeResource
import com.example.ottomatic.domain.registry.SmartHomeHubs
import com.example.ottomatic.engine.DefaultExecutionContext
import com.example.ottomatic.engine.ExecutionRoute
import com.example.ottomatic.engine.FakeAi
import com.example.ottomatic.engine.FakeTurn
import com.example.ottomatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.After
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
 *
 * It absorbed `action.ai_agent`, so the second half here is the tool switch — and the
 * case that matters most is the switch being **off**, which has to behave exactly as
 * this node did before profiles existed however much the chosen model is allowed to do.
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
        val out = action.execute(AiPromptConfig(modelRef = MODEL, prompt = "six times seven"), context)
        assertEquals("42", out.value)
    }

    /**
     * Which model a prompt goes through is a per-node decision — quota is per key —
     * so it has to arrive at the facade rather than being resolved from some implicit
     * default. A node that silently used whichever profile sorted first would spend a
     * key the user did not choose.
     */
    @Test
    fun `the chosen model reaches the facade`() = runBlocking {
        action.execute(AiPromptConfig(modelRef = "work-model", prompt = "hi"), context)
        assertEquals("work-model", ai.requests.single().modelRef)
    }

    @Test
    fun `the prompt, instruction and limit all reach the facade`() = runBlocking {
        action.execute(
            AiPromptConfig(
                modelRef = MODEL,
                prompt = "summarise this",
                systemInstruction = "Answer in one sentence",
                maxOutputTokens = 77,
            ),
            context,
        )
        val request = ai.requests.single()
        assertEquals("summarise this", request.prompt)
        assertEquals("Answer in one sentence", request.systemInstruction)
        assertEquals(77, request.maxOutputTokens)
    }

    @Test
    fun `a failure lands on the fallback and still pulses out`() = runBlocking {
        ai.reply = AiReply(error = "API key not valid.")
        val out = action.execute(
            AiPromptConfig(modelRef = MODEL, prompt = "anything", fallback = "unknown"),
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
        action.execute(AiPromptConfig(modelRef = MODEL, prompt = "anything"), context)
        val error = logs.single { it.level == LogLevel.ERROR }
        assertTrue(error.message.contains("API key not valid."))
    }

    @Test
    fun `an unset fallback means the port carries empty text rather than the error`() = runBlocking {
        ai.reply = AiReply(error = "Quota exceeded")
        val out = action.execute(AiPromptConfig(modelRef = MODEL, prompt = "anything"), context)
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
        val out = action.execute(AiPromptConfig(modelRef = MODEL, prompt = "tell me a story"), context)
        assertEquals("It was the best of", out.value)
        assertTrue(logs.any { it.level == LogLevel.WARN && it.message.contains("cut off") })
    }

    @Test
    fun `a blank prompt is never sent and never bills for itself`() = runBlocking {
        val out = action.execute(
            AiPromptConfig(modelRef = MODEL, prompt = "", fallback = "nothing asked"),
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
            AiPromptConfig(modelRef = MODEL, prompt = "hello", fallback = "offline"),
            bare,
        )
        assertEquals("offline", out.value)
        assertTrue(logs.any { it.level == LogLevel.ERROR && it.message.contains("no AI connection", true) })
    }

    // ---- the tool switch -------------------------------------------------------

    /**
     * **The default, and the reason it is the default.** A node placed before profiles
     * existed must behave exactly as it did afterwards, so a model granting twenty
     * tools changes nothing until somebody switches this on. The library is not even
     * asked, which is what makes that guarantee visible rather than incidental.
     */
    @Test
    fun `with tools off the profile's permissions are never even looked up`() = runBlocking {
        ai.tools = "value.battery"
        action.execute(AiPromptConfig(modelRef = MODEL, prompt = "hello"), context)
        assertEquals(emptyList<String>(), ai.toolLookups)
        assertEquals(emptyList<Any>(), ai.offered.single())
    }

    @Test
    fun `with tools on the profile's list is what the model is offered`() = runBlocking {
        ai.tools = "value.battery"
        action.execute(AiPromptConfig(modelRef = MODEL, prompt = "how full is it?", useTools = true), context)
        assertEquals(listOf(MODEL), ai.toolLookups)
        assertEquals(listOf("value_battery"), ai.offered.single().map { it.name })
    }

    /** A profile that grants nothing is a node that asks a question, not a broken one. */
    @Test
    fun `tools on with an empty list still answers`() = runBlocking {
        ai.reply = AiReply(text = "I cannot check")
        val out = action.execute(
            AiPromptConfig(modelRef = MODEL, prompt = "how full is it?", useTools = true),
            context,
        )
        assertEquals("I cannot check", out.value)
        assertEquals(emptyList<Any>(), ai.offered.single())
    }

    @Test
    fun `a tool the model asks for is actually run and its answer comes back`() = runBlocking {
        ai.tools = "value.battery"
        ai.turns += FakeTurn.call("value_battery")
        ai.turns += FakeTurn.says("The battery is at 50%")

        val out = action.execute(
            AiPromptConfig(modelRef = MODEL, prompt = "how full is it?", useTools = true),
            context,
        )
        assertEquals("The battery is at 50%", out.value)
        assertEquals(listOf("value_battery"), ai.invoked.map { it.name })
        // What is pinned is the wiring — the call reached a runner and came back with
        // something. Whether a bare test context can actually read a battery is
        // `NodeToolRunner`'s subject, not this node's.
        assertEquals(1, ai.results.size)
    }

    /** The tool half fails the same way the plain half does: onto the fallback, pulsing `out`. */
    @Test
    fun `a failure in the tool exchange lands on the fallback and still pulses out`() = runBlocking {
        ai.tools = "value.battery"
        ai.reply = AiReply(error = "The AI was still using tools after 8 turns and was stopped")
        val out = action.execute(
            AiPromptConfig(modelRef = MODEL, prompt = "do it", useTools = true, fallback = "gave up"),
            context,
        )
        assertEquals("gave up", out.value)
        assertEquals(ExecutionRoute.OUT, out.route)
        assertFalse(out.halt)
        assertTrue(logs.any { it.level == LogLevel.ERROR && it.message.contains("8 turns") })
    }

    // ---- adjusting the profile at the node ---------------------------------------

    /** The ordinary case, and the one that must not regress: blank means the profile. */
    @Test
    fun `no overrides means exactly what the profile allows`() = runBlocking {
        ai.tools = "value.battery"
        action.execute(AiPromptConfig(modelRef = MODEL, prompt = "hi", useTools = true), context)
        assertEquals(listOf("value_battery"), ai.offered.single().map { it.name })
    }

    @Test
    fun `an override adds a tool the profile does not grant`() = runBlocking {
        ai.tools = "value.battery"
        action.execute(
            AiPromptConfig(
                modelRef = MODEL,
                prompt = "hi",
                useTools = true,
                toolOverrides = "action.notify",
            ),
            context,
        )
        assertEquals(listOf("value_battery", "action_notify"), ai.offered.single().map { it.name })
    }

    @Test
    fun `an override removes a tool the profile does grant`() = runBlocking {
        ai.tools = "value.battery\naction.notify"
        action.execute(
            AiPromptConfig(
                modelRef = MODEL,
                prompt = "hi",
                useTools = true,
                toolOverrides = "-action.notify",
            ),
            context,
        )
        assertEquals(listOf("value_battery"), ai.offered.single().map { it.name })
    }

    /**
     * **The request itself.** The profile fixes the scene; this node does not, so the
     * model is handed the scenes to choose from instead.
     */
    @Test
    fun `an override can unpin a field so the model chooses it`() = runBlocking {
        SmartHomeHubs.hydrate(
            mapOf("hub-1" to listOf(SmartHomeResource(SmartHomeTargetKind.SCENE, rid = "s1", name = "Dinner"))),
        )
        ai.tools = """action.light_scene {"scene":"sh:hub-1|SCENE|s1|Dinner"}"""

        val pinned = AiPromptConfig(modelRef = MODEL, prompt = "hi", useTools = true)
        action.execute(pinned, context)
        assertFalse("the profile pins it", "scene" in ai.offered.single().single().parameters.map { it.name })

        ai.offered.clear()
        action.execute(pinned.copy(toolOverrides = "action.light_scene"), context)
        val scene = ai.offered.single().single().parameters.single { it.name == "scene" }
        assertEquals(listOf("sh:hub-1|SCENE|s1|Dinner"), (scene.schema as AiParamSchema.Text).options)
    }

    /** Nothing else bounds a loop the model drives, so a limit that never arrives is unbounded. */
    @Test
    fun `the turn limit reaches the facade rather than being left at the default`() = runBlocking {
        ai.tools = "value.battery"
        repeat(6) { ai.turns += FakeTurn.call("value_battery") }
        action.execute(
            AiPromptConfig(modelRef = MODEL, prompt = "do it", useTools = true, maxTurns = 2),
            context,
        )
        assertEquals(2, ai.invoked.size)
    }

    @After
    fun clearRegistries() = SmartHomeHubs.reset()

    private companion object {
        const val MODEL = "model-profile-id"
    }
}
