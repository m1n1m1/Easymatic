package com.example.ottomatic.data.ai

import com.example.ottomatic.core.service.AiModel
import com.example.ottomatic.core.service.AiParam
import com.example.ottomatic.core.service.AiParamSchema
import com.example.ottomatic.core.service.AiRequest
import com.example.ottomatic.core.service.AiTool
import com.example.ottomatic.core.service.AiToolCall
import com.example.ottomatic.core.service.AiToolResult
import com.example.ottomatic.domain.model.AiConnection
import com.example.ottomatic.domain.model.AiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tool half of all three wire formats.
 *
 * **One file rather than three additions**, because the assertions that matter most
 * here are *cross-provider* and no per-provider file could hold them: that none of
 * the three reports a tool-calling turn as an empty answer, that each echoes the
 * assistant's own turn back in whatever shape it demands, and that a result is
 * addressed the way that provider addresses it. Each protocol's single-prompt
 * envelope stays pinned by its own file, untouched.
 */
class AiToolProtocolTest {

    private val tool = AiTool(
        name = "value_battery",
        description = "Reads the phone's battery level",
        parameters = listOf(AiParam("scale", AiParamSchema.Text(listOf("percent")))),
    )

    private fun request(model: AiModel = AiModel.FAST) = AiRequest(
        connectionId = "connection-id",
        prompt = "how full is the battery?",
        model = model,
    )

    private fun connection(provider: AiProvider) =
        AiConnection(id = "connection-id", name = "Test", provider = provider, fastModel = "m")

    /** The exchange every provider is asked to render: ask, model calls, tool answers. */
    private fun exchangeAfter(turn: AiTurn, result: AiToolResult) = listOf(
        AiExchange.Ask("how full is the battery?"),
        AiExchange.Said(turn),
        AiExchange.Ran(listOf(turn.toolCalls.first() to result)),
    )

    // ---- what every provider must agree about ----------------------------------

    /**
     * The rule that would otherwise break every working request. All three protocols
     * correctly treat blank text as a failure for a *plain* reply — and a
     * tool-calling turn legitimately carries no text at all on two of the three.
     */
    @Test
    fun `no provider reports a tool-calling turn as an empty answer`() {
        assertEquals("", AnthropicProtocol.readTurn(200, ANTHROPIC_CALLS).error)
        assertEquals("", OpenAiProtocol.OpenAi.readTurn(200, OPENAI_CALLS).error)
        assertEquals("", GeminiProtocol.readTurn(200, GEMINI_CALLS).error)
    }

    @Test
    fun `every provider reads the call's name and arguments`() {
        listOf(
            AnthropicProtocol.readTurn(200, ANTHROPIC_CALLS),
            OpenAiProtocol.OpenAi.readTurn(200, OPENAI_CALLS),
            GeminiProtocol.readTurn(200, GEMINI_CALLS),
        ).forEach { turn ->
            assertEquals(1, turn.toolCalls.size)
            assertEquals("value_battery", turn.toolCalls.single().name)
            assertEquals(mapOf("scale" to "percent"), turn.toolCalls.single().arguments)
        }
    }

    /**
     * Without a kept turn there is nothing to echo, and both Anthropic and OpenAI
     * refuse a continuation that does not carry it.
     */
    @Test
    fun `every provider keeps the assistant turn for the echo`() {
        listOf(
            AnthropicProtocol.readTurn(200, ANTHROPIC_CALLS),
            OpenAiProtocol.OpenAi.readTurn(200, OPENAI_CALLS),
            GeminiProtocol.readTurn(200, GEMINI_CALLS),
        ).forEach { assertTrue(it.raw != null) }
    }

    /** A plain answer still ends the exchange rather than continuing it. */
    @Test
    fun `a turn with no calls in it is a finished answer for every provider`() {
        val anthropic = AnthropicProtocol.readTurn(200, ANTHROPIC_TEXT)
        val openAi = OpenAiProtocol.OpenAi.readTurn(200, OPENAI_TEXT)
        val gemini = GeminiProtocol.readTurn(200, GEMINI_TEXT)
        listOf(anthropic, openAi, gemini).forEach {
            assertFalse(it.wantsTools)
            assertEquals("72%", it.text)
        }
    }

    /**
     * A server that has never heard of tools answers with its own sentence, and that
     * sentence is what the user can act on — this is the degradation the plan chose
     * over a capability flag nobody can answer at setup time.
     */
    @Test
    fun `a server refusing the tools field reports its own words`() {
        val turn = OpenAiProtocol.SelfHosted.readTurn(
            status = 400,
            body = """{"error":{"message":"tools are not supported by this model"}}""",
        )
        assertTrue(turn.error.contains("tools are not supported"))
        assertTrue(turn.error.contains("400"))
    }

    // ---- Anthropic --------------------------------------------------------------

    /**
     * `content` stops being a bare string in this mode, because a tool result must be
     * a block — so the whole conversation switches to blocks rather than mixing two
     * shapes.
     */
    @Test
    fun `Anthropic sends every turn as a block array`() {
        val body = AnthropicProtocol.conversationBody(
            exchange = listOf(AiExchange.Ask("hello")),
            tools = listOf(tool),
            request = request(),
            connection = connection(AiProvider.ANTHROPIC),
        )
        assertTrue(body.contains("""{"type":"text","text":"hello"}"""))
        assertTrue(body.contains(""""input_schema":{"type":"object""""))
    }

    /**
     * **The strictest rule in the feature.** `readReply` deliberately drops thinking
     * blocks so one is never read as the answer; the echo must keep them, or the
     * server refuses the continuation.
     */
    @Test
    fun `Anthropic echoes the assistant turn verbatim, thinking blocks included`() {
        val turn = AnthropicProtocol.readTurn(200, ANTHROPIC_THINKS_THEN_CALLS)
        // The reply reader still hides it, which is what makes this a real risk.
        assertEquals("", AnthropicProtocol.readReply(200, ANTHROPIC_THINKS_THEN_CALLS).text)

        val body = AnthropicProtocol.conversationBody(
            exchange = exchangeAfter(turn, AiToolResult("72")),
            tools = listOf(tool),
            request = request(),
            connection = connection(AiProvider.ANTHROPIC),
        )
        assertTrue(body.contains(""""type":"thinking""""))
        assertTrue(body.contains(""""signature":"abc""""))
        assertTrue(body.contains(""""role":"assistant""""))
    }

    @Test
    fun `Anthropic addresses a result by the id the server minted`() {
        val turn = AnthropicProtocol.readTurn(200, ANTHROPIC_CALLS)
        val body = AnthropicProtocol.conversationBody(
            exchange = exchangeAfter(turn, AiToolResult("72")),
            tools = listOf(tool),
            request = request(),
            connection = connection(AiProvider.ANTHROPIC),
        )
        assertTrue(body.contains(""""type":"tool_result""""))
        assertTrue(body.contains(""""tool_use_id":"toolu_01""""))
    }

    @Test
    fun `Anthropic marks a failed tool so the model can try something else`() {
        val turn = AnthropicProtocol.readTurn(200, ANTHROPIC_CALLS)
        val body = AnthropicProtocol.conversationBody(
            exchange = exchangeAfter(turn, AiToolResult("no such light", isError = true)),
            tools = listOf(tool),
            request = request(),
            connection = connection(AiProvider.ANTHROPIC),
        )
        assertTrue(body.contains(""""is_error":true"""))
    }

    // ---- OpenAI -----------------------------------------------------------------

    @Test
    fun `OpenAI wraps a tool in its function envelope`() {
        val body = OpenAiProtocol.OpenAi.conversationBody(
            exchange = listOf(AiExchange.Ask("hello")),
            tools = listOf(tool),
            request = request(),
            connection = connection(AiProvider.OPENAI),
        )
        assertTrue(body.contains(""""type":"function""""))
        assertTrue(body.contains(""""name":"value_battery""""))
        assertTrue(body.contains(""""parameters":{"type":"object""""))
    }

    /**
     * The one place the three genuinely disagree about more than a key name:
     * `function.arguments` is a JSON **string** here, not an object.
     */
    @Test
    fun `OpenAI parses arguments out of the JSON string they arrive in`() {
        val turn = OpenAiProtocol.OpenAi.readTurn(200, OPENAI_CALLS)
        assertEquals(mapOf("scale" to "percent"), turn.toolCalls.single().arguments)
    }

    /**
     * Malformed argument JSON yields no arguments rather than failing the turn — the
     * node then decodes its config defaults, which is the degradation a blank form
     * field already has.
     */
    @Test
    fun `OpenAI survives malformed argument JSON`() {
        val turn = OpenAiProtocol.OpenAi.readTurn(
            status = 200,
            body = """
                {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                  {"id":"call_1","type":"function","function":{"name":"value_battery","arguments":"{oops"}}]},
                  "finish_reason":"tool_calls"}]}
            """.trimIndent(),
        )
        assertEquals("", turn.error)
        assertEquals(emptyMap<String, String>(), turn.toolCalls.single().arguments)
    }

    @Test
    fun `OpenAI answers on a tool role addressed by call id`() {
        val turn = OpenAiProtocol.OpenAi.readTurn(200, OPENAI_CALLS)
        val body = OpenAiProtocol.OpenAi.conversationBody(
            exchange = exchangeAfter(turn, AiToolResult("72")),
            tools = listOf(tool),
            request = request(),
            connection = connection(AiProvider.OPENAI),
        )
        assertTrue(body.contains(""""role":"tool""""))
        assertTrue(body.contains(""""tool_call_id":"call_1""""))
    }

    // ---- Gemini -----------------------------------------------------------------

    @Test
    fun `Gemini nests declarations under a single tools entry`() {
        val body = GeminiProtocol.conversationBody(
            exchange = listOf(AiExchange.Ask("hello")),
            tools = listOf(tool),
            request = request(),
            connection = connection(AiProvider.GEMINI),
        )
        assertTrue(body.contains(""""tools":[{"functionDeclarations":["""))
        // The single-prompt body's own rules still hold in this mode.
        assertTrue(body.contains(""""thinkingLevel""""))
        assertFalse(body.contains("thinkingBudget"))
    }

    /**
     * Gemini mints no call id at all, so one is synthesized — and the index is what
     * keeps two calls to the same tool distinct.
     */
    @Test
    fun `Gemini synthesizes distinct ids for repeated calls`() {
        val turn = GeminiProtocol.readTurn(
            status = 200,
            body = """
                {"candidates":[{"content":{"parts":[
                  {"functionCall":{"name":"value_battery","args":{}}},
                  {"functionCall":{"name":"value_battery","args":{}}}]},"finishReason":"STOP"}]}
            """.trimIndent(),
        )
        assertEquals(2, turn.toolCalls.size)
        assertEquals(2, turn.toolCalls.map { it.id }.distinct().size)
    }

    /** A model may narrate and call in the same breath; dropping either half loses something. */
    @Test
    fun `Gemini reads text and a call from the same turn`() {
        val turn = GeminiProtocol.readTurn(
            status = 200,
            body = """
                {"candidates":[{"content":{"parts":[
                  {"text":"Let me check."},
                  {"functionCall":{"name":"value_battery","args":{}}}]},"finishReason":"STOP"}]}
            """.trimIndent(),
        )
        assertEquals("Let me check.", turn.text)
        assertTrue(turn.wantsTools)
    }

    /**
     * This API matches a response to its call by **name**, and `response` must be an
     * object rather than the bare string the other two accept.
     */
    @Test
    fun `Gemini answers by name inside a response object`() {
        val turn = GeminiProtocol.readTurn(200, GEMINI_CALLS)
        val body = GeminiProtocol.conversationBody(
            exchange = exchangeAfter(turn, AiToolResult("72")),
            tools = listOf(tool),
            request = request(),
            connection = connection(AiProvider.GEMINI),
        )
        assertTrue(body.contains(""""functionResponse":{"name":"value_battery","response":{"result":"72"}}"""))
        assertTrue(body.contains(""""role":"model""""))
    }

    @Test
    fun `Gemini reports a failed tool under its own key`() {
        val turn = GeminiProtocol.readTurn(200, GEMINI_CALLS)
        val body = GeminiProtocol.conversationBody(
            exchange = exchangeAfter(turn, AiToolResult("unreachable", isError = true)),
            tools = listOf(tool),
            request = request(),
            connection = connection(AiProvider.GEMINI),
        )
        assertTrue(body.contains(""""response":{"error":"unreachable"}"""))
    }

    private companion object {
        val ANTHROPIC_CALLS = """
            {"content":[{"type":"tool_use","id":"toolu_01","name":"value_battery",
             "input":{"scale":"percent"}}],"stop_reason":"tool_use"}
        """.trimIndent()

        val ANTHROPIC_THINKS_THEN_CALLS = """
            {"content":[{"type":"thinking","thinking":"checking","signature":"abc"},
             {"type":"tool_use","id":"toolu_01","name":"value_battery","input":{"scale":"percent"}}],
             "stop_reason":"tool_use"}
        """.trimIndent()

        val ANTHROPIC_TEXT = """
            {"content":[{"type":"text","text":"72%"}],"stop_reason":"end_turn"}
        """.trimIndent()

        val OPENAI_CALLS = """
            {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
              {"id":"call_1","type":"function","function":{"name":"value_battery",
               "arguments":"{\"scale\":\"percent\"}"}}]},"finish_reason":"tool_calls"}]}
        """.trimIndent()

        val OPENAI_TEXT = """
            {"choices":[{"message":{"role":"assistant","content":"72%"},"finish_reason":"stop"}]}
        """.trimIndent()

        val GEMINI_CALLS = """
            {"candidates":[{"content":{"parts":[
              {"functionCall":{"name":"value_battery","args":{"scale":"percent"}}}]},
              "finishReason":"STOP"}]}
        """.trimIndent()

        val GEMINI_TEXT = """
            {"candidates":[{"content":{"parts":[{"text":"72%"}]},"finishReason":"STOP"}]}
        """.trimIndent()
    }
}
