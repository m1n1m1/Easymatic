package com.example.ottomatic.data.ai

import com.example.ottomatic.core.service.AiModel
import com.example.ottomatic.core.service.AiRequest
import com.example.ottomatic.domain.model.AiConnection
import com.example.ottomatic.domain.model.AiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The half of the Claude integration that can be tested without a device, a key or a
 * network — and the half where the mistakes are.
 *
 * [GeminiProtocolTest]'s shape for its reason: a refused key, an exhausted credit
 * balance, a reply stopped at the token bound and a response whose first block is
 * thinking rather than text are all real shapes a live server produces on its own
 * schedule and none on demand.
 */
class AnthropicProtocolTest {

    private val connection = AiConnection(
        id = "connection-id",
        name = "Claude",
        provider = AiProvider.ANTHROPIC,
    )

    private fun request(
        maxOutputTokens: Int = 100,
        systemInstruction: String = "",
    ) = AiRequest(
        modelRef = TEST_MODEL_REF,
        prompt = "hi",
        maxOutputTokens = maxOutputTokens,
        systemInstruction = systemInstruction,
    )

    private fun body(
        request: AiRequest,
        on: AiConnection = connection,
        effort: AiModel = AiModel.FAST,
        modelId: String = "",
    ) = AnthropicProtocol.requestBody(request, target(on, effort, modelId))

    private fun maxTokensIn(body: String): Int =
        Regex("\"max_tokens\":(\\d+)").find(body)!!.groupValues[1].toInt()

    @Test
    fun `a plain answer is read off the text block`() {
        val reply = AnthropicProtocol.readReply(
            status = 200,
            body = """
                {"content":[{"type":"text","text":"Battery is low"}],"stop_reason":"end_turn"}
            """.trimIndent(),
        )
        assertEquals("Battery is low", reply.text)
        assertEquals("", reply.error)
        assertFalse(reply.truncated)
    }

    /**
     * The thinking tiers answer with a thinking block first. Reading `content[0]`
     * would hand the user an empty string on every request that worked.
     */
    @Test
    fun `a thinking block ahead of the answer is skipped rather than read as the answer`() {
        val reply = AnthropicProtocol.readReply(
            status = 200,
            body = """
                {"content":[{"type":"thinking","thinking":""},{"type":"text","text":"42"}],
                 "stop_reason":"end_turn"}
            """.trimIndent(),
        )
        assertEquals("42", reply.text)
    }

    @Test
    fun `several text blocks are joined rather than only the first being read`() {
        val reply = AnthropicProtocol.readReply(
            status = 200,
            body = """{"content":[{"type":"text","text":"one "},{"type":"text","text":"two"}]}""",
        )
        assertEquals("one two", reply.text)
    }

    /** Real output that stops mid-sentence, which a macro will happily go on and send. */
    @Test
    fun `an answer stopped at the token bound is returned and flagged`() {
        val reply = AnthropicProtocol.readReply(
            status = 200,
            body = """
                {"content":[{"type":"text","text":"It was the best of"}],"stop_reason":"max_tokens"}
            """.trimIndent(),
        )
        assertEquals("It was the best of", reply.text)
        assertEquals("", reply.error)
        assertTrue(reply.truncated)
    }

    /** A thinking model that spent the whole budget before writing anything. */
    @Test
    fun `a reply with no text at all is a failure naming the limit`() {
        val reply = AnthropicProtocol.readReply(
            status = 200,
            body = """{"content":[{"type":"thinking","thinking":""}],"stop_reason":"max_tokens"}""",
        )
        assertEquals("", reply.text)
        assertTrue(reply.error.contains("cut off"))
        assertTrue(reply.error.contains("limit"))
    }

    @Test
    fun `a refused key reports the server's own sentence rather than the status`() {
        val reply = AnthropicProtocol.readReply(
            status = 401,
            body = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""",
        )
        assertEquals("", reply.text)
        assertTrue(reply.error.contains("invalid x-api-key"))
        assertTrue(reply.error.contains("401"))
    }

    /** The commonest Anthropic failure, and one a status code says nothing about. */
    @Test
    fun `an exhausted credit balance is reported in the server's words`() {
        val reply = AnthropicProtocol.readReply(
            status = 400,
            body = """
                {"type":"error","error":{"type":"invalid_request_error",
                 "message":"Your credit balance is too low to access the Anthropic API."}}
            """.trimIndent(),
        )
        assertTrue(reply.error.contains("credit balance"))
    }

    @Test
    fun `a declined prompt says so rather than reading as an empty success`() {
        val reply = AnthropicProtocol.readReply(
            status = 200,
            body = """{"content":[],"stop_reason":"refusal"}""",
        )
        assertEquals("", reply.text)
        assertTrue(reply.error.contains("declined"))
    }

    /** What the transport reports for a request that never left the phone. */
    @Test
    fun `no response at all reads as a connection problem and names no HTTP status`() {
        val reply = AnthropicProtocol.readReply(status = NO_RESPONSE, body = "")
        assertTrue(reply.error.contains("connection"))
        assertFalse(reply.error.contains("HTTP"))
    }

    @Test
    fun `an unknown field in the envelope does not stop the answer being read`() {
        val reply = AnthropicProtocol.readReply(
            status = 200,
            body = """
                {"content":[{"type":"text","text":"ok","citations":null}],"usage":{"input_tokens":3},
                 "something_new":7}
            """.trimIndent(),
        )
        assertEquals("ok", reply.text)
    }

    @Test
    fun `a blank standing instruction is left out of the body entirely`() {
        val sent = body(request())
        assertFalse(sent.contains("\"system\""))
        assertTrue(sent.contains("hi"))
    }

    @Test
    fun `a standing instruction that was given is sent as its own field`() {
        val sent = body(request(systemInstruction = "Answer in German"))
        assertTrue(sent.contains("\"system\""))
        assertTrue(sent.contains("Answer in German"))
    }

    /**
     * The per-tier branch that is a 400 if it is wrong in one direction and an empty
     * answer if it is wrong in the other: `claude-haiku-4-5` predates adaptive
     * thinking and rejects the field outright, where the other two need it.
     */
    @Test
    fun `the fast tier sends no thinking field and the slower tiers do`() {
        assertFalse(
            "the fast tier's model rejects a thinking field outright",
            body(request(), effort = AiModel.FAST).contains("\"thinking\""),
        )
        assertTrue(body(request(), effort = AiModel.BALANCED).contains("\"thinking\""))
        assertTrue(body(request(), effort = AiModel.THOROUGH).contains("\"thinking\""))
    }

    /**
     * The trap: thinking tokens come out of the same `max_tokens` budget as the
     * reply, so a tier that thinks has to ask for more than the user's limit or it
     * can answer nothing at all. The fast tier does not think, so it asks for
     * exactly what was requested.
     */
    @Test
    fun `every thinking tier gets headroom on top of the requested reply length`() {
        assertEquals(100, maxTokensIn(body(request(), effort = AiModel.FAST)))
        assertTrue(maxTokensIn(body(request(), effort = AiModel.BALANCED)) > 100)
        assertTrue(
            maxTokensIn(body(request(), effort = AiModel.THOROUGH)) >
                maxTokensIn(body(request(), effort = AiModel.BALANCED)),
        )
    }

    @Test
    fun `a zero reply limit still asks for at least one token`() {
        assertTrue(maxTokensIn(body(request(maxOutputTokens = 0), effort = AiModel.FAST)) >= 1)
    }

    /**
     * A dated snapshot is an id that gets retired on somebody else's schedule; an
     * alias is one the provider re-points for you. Gemini's table has been broken
     * twice by exactly this, and there is no reason to learn it again here.
     */
    @Test
    fun `no tier names a dated snapshot or a preview model`() {
        for (model in AiModel.entries) {
            val id = AnthropicProtocol.modelId(model)
            assertFalse("$model names a preview id, which gets withdrawn", id.contains("preview"))
            assertFalse(
                "$model names a dated snapshot, which gets retired — use the alias",
                Regex("-\\d{8}$").containsMatchIn(id),
            )
        }
    }

    @Test
    fun `every model tier maps to a model id that reaches the messages endpoint`() {
        for (model in AiModel.entries) {
            val id = AnthropicProtocol.modelId(model)
            assertTrue("$model has no model id", id.isNotBlank())
            assertTrue(body(request(), effort = model).contains(id))
        }
        val endpoint = AnthropicProtocol.endpoint(target(connection))
        assertTrue(endpoint.startsWith("https://"))
        assertTrue(endpoint.endsWith("/messages"))
    }

    /**
     * `anthropic-version` is not a nicety: a request without it is refused outright,
     * and the pin is what stops a future server default from changing the envelope
     * this file parses.
     */
    @Test
    fun `the api version header is always sent`() {
        val headers = AnthropicProtocol.headers("key-123")
        assertTrue(headers.containsKey("anthropic-version"))
        assertEquals("key-123", headers["x-api-key"])
    }

    @Test
    fun `a model named on the profile wins over the built-in table`() {
        val sent = body(request(), effort = AiModel.THOROUGH, modelId = "claude-something-new")
        assertTrue(sent.contains("claude-something-new"))
    }

    @Test
    fun `a listing is read as its ids`() {
        val models = AnthropicProtocol.readModels(
            status = 200,
            body = """{"data":[{"id":"claude-opus-5","display_name":"Claude Opus 5"}],"has_more":false}""",
        )
        assertEquals(listOf("claude-opus-5"), models.ids)
        assertEquals("", models.error)
    }

    /**
     * The display name is read because it is the string a person recognises — a chooser
     * showing `claude-opus-5` beside "Claude Opus 5" is a chooser you can read.
     */
    @Test
    fun `the display name is kept beside the id`() {
        val models = AnthropicProtocol.readModels(
            status = 200,
            body = """{"data":[{"id":"claude-opus-5","display_name":"Claude Opus 5"}],"has_more":false}""",
        )
        assertEquals("Claude Opus 5", models.models.single().label)
    }

    /**
     * This API publishes no modalities, and the chooser must read that as "not published"
     * rather than as "accepts nothing" — otherwise every Claude model disappears the
     * moment somebody ticks a filter chip.
     */
    @Test
    fun `no modalities are claimed, which is not the same as claiming none`() {
        val models = AnthropicProtocol.readModels(
            status = 200,
            body = """{"data":[{"id":"claude-opus-5","display_name":"Claude Opus 5"}]}""",
        )
        assertNull(models.models.single().modalities)
    }
}
