package com.example.ottomatic.data.ai

import com.example.ottomatic.core.service.AiAudio
import com.example.ottomatic.core.service.AiModel
import com.example.ottomatic.core.service.AiParam
import com.example.ottomatic.core.service.AiParamSchema
import com.example.ottomatic.core.service.AiRequest
import com.example.ottomatic.core.service.AiTool
import com.example.ottomatic.core.service.AiToolCall
import com.example.ottomatic.core.service.AiToolLimits
import com.example.ottomatic.core.service.AiToolResult
import com.example.ottomatic.data.AiConnectionRepository
import com.example.ottomatic.data.security.FakeSecrets
import com.example.ottomatic.domain.model.AiConnection
import com.example.ottomatic.domain.model.AiModelProfile
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

    private fun request(modelRef: String, prompt: String = "hi") =
        AiRequest(modelRef = modelRef, prompt = prompt)

    /** A connection with one model on it, which is what a node's ref has to resolve to. */
    private suspend fun AiConnectionRepository.withModel(
        name: String,
        provider: AiProvider,
        modelId: String = "",
    ): String {
        val created = create(name, provider)
        val profile = AiModelProfile(id = "${created.id}#p", name = "Model", modelId = modelId)
        upsert(get(created.id)!!.copy(models = listOf(profile)))
        return profile.id
    }

    // ---- combining the two standing instructions -------------------------------

    /**
     * The order is the design decision, not an implementation detail: the profile's
     * rules are the frame and the node's task arrives inside it.
     */
    @Test
    fun `both instructions are sent, the profile's first`() {
        assertEquals(
            "Answer in German.\n\nReply with one word.",
            combineInstructions("Answer in German.", "Reply with one word."),
        )
    }

    /**
     * The case that makes the change invisible to every macro written before it: a
     * profile with no standing instruction behaves exactly as it always did.
     */
    @Test
    fun `the node's instruction alone passes through untouched`() {
        assertEquals("Reply with one word.", combineInstructions("", "Reply with one word."))
    }

    @Test
    fun `the profile's instruction alone passes through untouched`() {
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

    /**
     * The reply limit resolves the *opposite* way to the instructions, and that is the
     * decision worth pinning: two prompts combine because a persona and a task are both
     * true at once, where two numbers cannot both hold and the specific one has to win.
     *
     * An Ask AI node's "Longest reply" field is a visible, per-task decision. If the
     * profile overrode it, raising the room the graph assistant gets would quietly
     * multiply what every macro on that key may spend.
     */
    @Test
    fun `a stated reply limit beats the profile's default`() {
        assertEquals(200, replyLimit(requested = 200, profileLimit = 8_192))
    }

    /** How the graph assistant asks: it has no field of its own, so the profile governs it. */
    @Test
    fun `no stated limit falls back to the profile`() {
        assertEquals(8_192, replyLimit(requested = 0, profileLimit = 8_192))
    }

    /**
     * A profile that says nothing either — an older one decoded before the field existed,
     * or one typed empty — must not resolve to a model asked for zero tokens, which would
     * answer nothing at all.
     */
    @Test
    fun `neither saying anything falls back to the built-in default`() {
        assertEquals(AiRequest.DEFAULT_MAX_OUTPUT_TOKENS, replyLimit(requested = 0, profileLimit = 0))
        assertEquals(AiRequest.DEFAULT_MAX_OUTPUT_TOKENS, replyLimit(requested = -5, profileLimit = -1))
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
        val blank = target(com.example.ottomatic.domain.model.AiConnection(id = "x", name = "x"))
        assertNotEquals(openAi.endpoint(blank), openRouter.endpoint(blank))
    }

    // ---- the guards that never reach the network -------------------------------

    @Test
    fun `a blank prompt is refused without a model being looked up`() = runBlocking {
        val reply = RoutingAi(repository()).complete(request(modelRef = "anything", prompt = "  "))
        assertTrue(reply.error.contains("No prompt"))
    }

    /**
     * The one guard sound had to relax, and the one place it must not be relaxed further.
     *
     * A blank prompt beside a clip is not an empty request — it is "just transcribe this",
     * which is both the commonest thing to ask of a recording and the thing a
     * transcription endpoint takes literally. Refusing it here would have made the audio
     * nodes' empty question mean nothing at all. The refusal has to survive for a request
     * carrying neither, which is still a node with an unfilled field.
     */
    @Test
    fun `a blank prompt carrying sound is not refused as empty`() = runBlocking {
        val repository = repository()
        val modelRef = repository.withModel("Claude", AiProvider.ANTHROPIC)
        repository.setKey(repository.connectionForProfile(modelRef)!!.id, "anything")

        val reply = RoutingAi(repository).complete(
            AiRequest(
                modelRef = modelRef,
                prompt = "",
                audio = listOf(AiAudio(base64 = "QUFB", mediaType = "audio/wav")),
            ),
        )

        // It gets past the blank-prompt guard and fails on this provider's own refusal,
        // which is the next thing that should stop it.
        assertFalse(reply.error.contains("No prompt"))
        assertTrue(reply.error.contains("cannot listen"))
    }

    /** Claude has no audio block at all, and no setting anywhere would change that. */
    @Test
    fun `a provider that cannot hear is refused before the network`() = runBlocking {
        val repository = repository()
        val modelRef = repository.withModel("Claude", AiProvider.ANTHROPIC)
        repository.setKey(repository.connectionForProfile(modelRef)!!.id, "anything")

        val reply = RoutingAi(repository).complete(
            AiRequest(
                modelRef = modelRef,
                prompt = "what was said?",
                audio = listOf(AiAudio(base64 = "QUFB", mediaType = "audio/wav")),
            ),
        )

        assertTrue(reply.error.contains("Claude cannot listen"))
        assertTrue(reply.error.contains("Gemini"))
    }

    /**
     * **A persona has no business in a transcript.** "Reply in German", "keep it to one
     * line", "you are a terse assistant" are all perfectly good standing instructions on a
     * profile, and every one of them would rewrite a verbatim transcript into something
     * else — with no field on the node to turn them off, because a node asking for a
     * transcript asked no question at all.
     */
    @Test
    fun `a transcription drops the profile's persona`() {
        val transcribing = AiRequest(
            modelRef = TEST_MODEL_REF,
            prompt = "",
            audio = listOf(AiAudio(base64 = "QUFB", mediaType = "audio/wav")),
        )

        assertEquals("", standingInstruction("Answer in German.", transcribing))
    }

    /** The same profile still frames an actual question, which is what it is for. */
    @Test
    fun `a question about a clip keeps the profile's persona`() {
        val asking = AiRequest(
            modelRef = TEST_MODEL_REF,
            prompt = "was that a yes?",
            audio = listOf(AiAudio(base64 = "QUFB", mediaType = "audio/wav")),
        )

        assertEquals("Answer in German.", standingInstruction("Answer in German.", asking))
    }

    /** A node's own instruction is the task, not the persona, so it survives either way. */
    @Test
    fun `a transcription keeps whatever the node itself asked for`() {
        val transcribing = AiRequest(
            modelRef = TEST_MODEL_REF,
            prompt = "",
            systemInstruction = "Use British spelling.",
            audio = listOf(AiAudio(base64 = "QUFB", mediaType = "audio/wav")),
        )

        assertEquals("Use British spelling.", standingInstruction("Answer in German.", transcribing))
    }

    /** A text prompt is untouched by any of this. */
    @Test
    fun `a request with no sound is unaffected by the transcription rule`() {
        val asking = AiRequest(modelRef = TEST_MODEL_REF, prompt = "hi")

        assertEquals("Answer in German.", standingInstruction("Answer in German.", asking))
    }

    @Test
    fun `a node with no model chosen says so rather than picking one`() = runBlocking {
        val reply = RoutingAi(repository()).complete(request(modelRef = ""))
        assertTrue(reply.error.contains("No AI model chosen"))
    }

    /**
     * The two messages must differ, because the fixes do: re-pick the model versus
     * paste the key in again.
     */
    @Test
    fun `a deleted model and an unreadable key are reported differently`() = runBlocking {
        val repository = repository()
        val ai = RoutingAi(repository)
        val deleted = ai.complete(request(modelRef = "never-existed")).error

        val modelRef = repository.withModel("Personal", AiProvider.GEMINI)
        val noKey = ai.complete(request(modelRef = modelRef)).error

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
        val modelRef = repository.withModel("Local", AiProvider.OPENAI_COMPATIBLE, modelId = "m")
        repository.setKey(repository.connectionForProfile(modelRef)!!.id, "anything")
        val reply = RoutingAi(repository).complete(request(modelRef = modelRef))
        assertTrue(reply.error.contains("server address"))
        assertFalse(reply.error.contains("connection"))
    }

    /**
     * The tool list crosses from the library as text, which is the seam that keeps
     * `engine/` from reaching into `data/`. A profile that is gone answers **blank**
     * rather than reporting: the caller's next step either way is to ask with no
     * tools, and [RoutingAi.complete] already has the sentence worth showing.
     */
    @Test
    fun `the tool list comes off the profile and a missing one answers blank`() = runBlocking {
        val repository = repository()
        val modelRef = repository.withModel("Personal", AiProvider.GEMINI)
        val connection = repository.connectionForProfile(modelRef)!!
        repository.upsert(
            connection.copy(models = connection.models.map { it.copy(tools = "action.notify") }),
        )
        val ai = RoutingAi(repository)
        assertEquals("action.notify", ai.toolsFor(modelRef))
        assertEquals("", ai.toolsFor("never-existed"))
    }

    /**
     * The guards are shared between the two paths deliberately, so this is the test
     * that they did not drift: a tool-using node must refuse the same things a
     * plain one does, before anything is billed.
     */
    @Test
    fun `the tool path is stopped by the same guards as the plain one`() = runBlocking {
        val reply = RoutingAi(repository()).converse(
            request = request(modelRef = "anything", prompt = "  "),
            tools = listOf(tool),
        ) { AiToolResult("") }
        assertTrue(reply.error.contains("No prompt"))
    }

    // ---- the exchange ----------------------------------------------------------
    //
    // Driven through a real protocol with a scripted transport: the sequence is the
    // whole behaviour here, and no live server produces one on demand.

    /** The ordinary shape: the model asks for something, gets it, and answers. */
    @Test
    fun `a tool call is run and its answer reaches the next turn`() = runBlocking {
        val sent = mutableListOf<String>()
        val ran = mutableListOf<AiToolCall>()
        val reply = exchange(sent, listOf(CALLS_BATTERY, ANSWERS)) { call ->
            ran += call
            AiToolResult("72")
        }

        assertEquals("The battery is at 72%", reply.text)
        assertEquals("", reply.error)
        assertEquals(listOf("value_battery"), ran.map { it.name })
        assertEquals(mapOf("scale" to "percent"), ran.single().arguments)

        // The second request must carry the assistant's own turn back and the result
        // beside it, or the server refuses the continuation.
        assertEquals(2, sent.size)
        assertTrue(sent[1].contains(""""role":"assistant""""))
        assertTrue(sent[1].contains(""""type":"tool_result""""))
        assertTrue(sent[1].contains("72"))
    }

    /**
     * A failing tool is not a failing run. The model is told, in the provider's own
     * terms, and gets to try something else — which is why [AiToolResult] carries a
     * flag rather than the runner throwing.
     */
    @Test
    fun `a tool that fails is reported to the model rather than ending the run`() = runBlocking {
        val sent = mutableListOf<String>()
        val reply = exchange(sent, listOf(CALLS_BATTERY, ANSWERS)) {
            AiToolResult("the sensor is unavailable", isError = true)
        }
        assertEquals("The battery is at 72%", reply.text)
        assertTrue(sent[1].contains(""""is_error":true"""))
    }

    /** Nothing else bounds a loop the model drives, so the cap is what stops it. */
    @Test
    fun `the turn cap stops the exchange and says so`() = runBlocking {
        val sent = mutableListOf<String>()
        var ran = 0
        val reply = exchange(sent, List(10) { CALLS_BATTERY }, maxTurns = 3) {
            ran++
            AiToolResult("72")
        }
        assertEquals(3, sent.size)
        assertEquals(3, ran)
        assertTrue(reply.error.contains("3 turns"))
        assertTrue(reply.error.contains("turn limit"))
        assertEquals("", reply.text)
    }

    /** The cap is a ceiling as well as a default; a node cannot ask for a hundred turns. */
    @Test
    fun `the turn count is clamped to the ceiling`() = runBlocking {
        val sent = mutableListOf<String>()
        exchange(sent, List(100) { CALLS_BATTERY }, maxTurns = 500) { AiToolResult("72") }
        assertEquals(AiToolLimits.MAX_TURNS, sent.size)
    }

    /**
     * Checked between turns rather than by cancelling one, so a tool halfway through
     * switching a light off is never stopped mid-write.
     */
    @Test
    fun `the overall budget stops the exchange before another turn is sent`() = runBlocking {
        val sent = mutableListOf<String>()
        val clock = ArrayDeque(listOf(0L, AiToolLimits.OVERALL_BUDGET_MS + 1))
        val reply = runToolExchange(
            protocol = AnthropicProtocol,
            request = request(modelRef = "c#p"),
            target = target(AiConnection(id = "c", name = "Claude", provider = AiProvider.ANTHROPIC)),
            tools = listOf(tool),
            maxTurns = 8,
            invoke = { AiToolResult("72") },
            now = { clock.removeFirstOrNull() ?: Long.MAX_VALUE },
            send = { body -> sent += body; 200 to CALLS_BATTERY },
        )
        assertEquals(0, sent.size)
        assertTrue(reply.error.contains("five minutes"))
    }

    /** A failed turn ends the exchange rather than being retried into the cap. */
    @Test
    fun `a refused request ends the exchange at once`() = runBlocking {
        val sent = mutableListOf<String>()
        val refusal = """{"error":{"message":"API key not valid"}}"""
        val reply = runToolExchange(
            protocol = AnthropicProtocol,
            request = request(modelRef = "c#p"),
            target = target(AiConnection(id = "c", name = "Claude", provider = AiProvider.ANTHROPIC)),
            tools = listOf(tool),
            maxTurns = 8,
            invoke = { AiToolResult("72") },
            send = { body -> sent += body; 401 to refusal },
        )
        assertEquals(1, sent.size)
        assertTrue(reply.error.contains("API key not valid"))
    }

    private val tool = AiTool(
        name = "value_battery",
        description = "Reads the phone's battery level",
        parameters = listOf(AiParam("scale", AiParamSchema.Text(listOf("percent")))),
    )

    /** Runs [replies] back in order through a real protocol, recording each body sent. */
    private suspend fun exchange(
        sent: MutableList<String>,
        replies: List<String>,
        maxTurns: Int = 8,
        invoke: suspend (AiToolCall) -> AiToolResult,
    ): com.example.ottomatic.core.service.AiReply {
        val queue = ArrayDeque(replies)
        return runToolExchange(
            protocol = AnthropicProtocol,
            request = request(modelRef = "c#p", prompt = "how full is the battery?"),
            target = target(AiConnection(id = "c", name = "Claude", provider = AiProvider.ANTHROPIC)),
            tools = listOf(tool),
            maxTurns = maxTurns,
            invoke = invoke,
            send = { body ->
                sent += body
                200 to (queue.removeFirstOrNull() ?: ANSWERS)
            },
        )
    }

    private companion object {
        val CALLS_BATTERY = """
            {"content":[{"type":"tool_use","id":"toolu_01","name":"value_battery",
             "input":{"scale":"percent"}}],"stop_reason":"tool_use"}
        """.trimIndent()

        val ANSWERS = """
            {"content":[{"type":"text","text":"The battery is at 72%"}],"stop_reason":"end_turn"}
        """.trimIndent()
    }
}
