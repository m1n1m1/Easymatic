package com.example.ottomatic.data.ai

import com.example.ottomatic.core.service.AiModel
import com.example.ottomatic.core.service.AiRequest
import com.example.ottomatic.domain.model.AiConnection
import com.example.ottomatic.domain.model.AiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The half of the Gemini integration that can be tested without a device, a key
 * or a network — and the half where the mistakes are.
 *
 * Every response below is a shape a real server sends and none of them can be
 * produced on demand, which is exactly why they are pinned here: a refused key, a
 * blocked prompt, an answer stopped at the token bound, and a candidate that came
 * back empty all have to be told apart, and each of them used to look identical
 * to "it did not work" from the node.
 */
class GeminiProtocolTest {

    /**
     * Any id at all. Which connection a prompt is billed to is resolved by
     * [RoutingAi] before this object is reached, so nothing here reads it — it is
     * carried only because [AiRequest] refuses to be built without one, which is
     * itself the point: there is no implicit connection to fall back on.
     */
    private val testConnection = "connection-id"

    /** A plain Gemini connection with nothing overridden. */
    private val connection = AiConnection(
        id = testConnection,
        name = "Gemini",
        provider = AiProvider.GEMINI,
    )

    /** A request with only the fields this object reads varied. */
    private fun request(model: AiModel, maxOutputTokens: Int = 100) =
        AiRequest(
            connectionId = testConnection,
            prompt = "hi",
            model = model,
            maxOutputTokens = maxOutputTokens,
        )

    private fun body(request: AiRequest, on: AiConnection = connection) =
        GeminiProtocol.requestBody(request, on)

    /** The `maxOutputTokens` actually sent for [model], less the [asked] reply limit. */
    private fun headroomOf(model: AiModel, asked: Int = 100): Int {
        val sent = body(request(model, asked))
        return Regex("\"maxOutputTokens\":(\\d+)").find(sent)!!.groupValues[1].toInt() - asked
    }

    @Test
    fun `a plain answer is read off the first candidate`() {
        val reply = GeminiProtocol.readReply(
            status = 200,
            body = """
                {"candidates":[{"content":{"parts":[{"text":"Battery is low"}]},"finishReason":"STOP"}]}
            """.trimIndent(),
        )
        assertEquals("Battery is low", reply.text)
        assertEquals("", reply.error)
        assertFalse(reply.truncated)
    }

    @Test
    fun `several parts are joined rather than only the first being read`() {
        val reply = GeminiProtocol.readReply(
            status = 200,
            body = """
                {"candidates":[{"content":{"parts":[{"text":"one "},{"text":"two"}]}}]}
            """.trimIndent(),
        )
        assertEquals("one two", reply.text)
    }

    /**
     * The case the node reports as a warning rather than a failure: real output
     * that stops mid-sentence, which a macro will happily go on and send.
     */
    @Test
    fun `an answer stopped at the token bound is returned and flagged`() {
        val reply = GeminiProtocol.readReply(
            status = 200,
            body = """
                {"candidates":[{"content":{"parts":[{"text":"It was the best of"}]},"finishReason":"MAX_TOKENS"}]}
            """.trimIndent(),
        )
        assertEquals("It was the best of", reply.text)
        assertEquals("", reply.error)
        assertTrue(reply.truncated)
    }

    /**
     * A thinking model that spent the whole budget before writing anything. It
     * has to name the limit, because the fix is to raise it and nothing else in
     * the response says so.
     */
    @Test
    fun `a candidate with no text at all is a failure naming the limit`() {
        val reply = GeminiProtocol.readReply(
            status = 200,
            body = """{"candidates":[{"content":{"parts":[]},"finishReason":"MAX_TOKENS"}]}""",
        )
        assertEquals("", reply.text)
        assertTrue(reply.error.contains("cut off"))
        assertTrue(reply.error.contains("limit"))
    }

    @Test
    fun `a refused key reports the server's own sentence rather than the status`() {
        val reply = GeminiProtocol.readReply(
            status = 400,
            body = """{"error":{"code":400,"message":"API key not valid.","status":"INVALID_ARGUMENT"}}""",
        )
        assertEquals("", reply.text)
        assertTrue(reply.error.contains("API key not valid."))
        assertTrue(reply.error.contains("400"))
    }

    @Test
    fun `an exhausted quota is reported in the server's words too`() {
        val reply = GeminiProtocol.readReply(
            status = 429,
            body = """
                {"error":{"code":429,"message":"Quota exceeded for quota metric.","status":"RESOURCE_EXHAUSTED"}}
            """.trimIndent(),
        )
        assertTrue(reply.error.contains("Quota exceeded"))
    }

    @Test
    fun `a blocked prompt says so rather than reading as an empty success`() {
        val reply = GeminiProtocol.readReply(
            status = 200,
            body = """{"promptFeedback":{"blockReason":"SAFETY"}}""",
        )
        assertEquals("", reply.text)
        assertTrue(reply.error.contains("blocked"))
        assertTrue(reply.error.contains("SAFETY"))
    }

    /** A gateway that answered HTML, or a proxy that answered nothing readable. */
    @Test
    fun `a body that is not JSON still reports the status rather than throwing`() {
        val reply = GeminiProtocol.readReply(status = 503, body = "<html>Service Unavailable</html>")
        assertTrue(reply.error.contains("503"))
    }

    /** What the transport reports for a request that never left the phone. */
    @Test
    fun `no response at all reads as a connection problem and names no HTTP status`() {
        val reply = GeminiProtocol.readReply(status = NO_RESPONSE, body = "")
        assertTrue(reply.error.contains("connection"))
        assertFalse(reply.error.contains("HTTP"))
    }

    @Test
    fun `an unknown field in the envelope does not stop the answer being read`() {
        val reply = GeminiProtocol.readReply(
            status = 200,
            body = """
                {"candidates":[{"content":{"parts":[{"text":"ok"}]},"safetyRatings":[],"tokensOfSomethingNew":7}],
                 "usageMetadata":{"totalTokenCount":11}}
            """.trimIndent(),
        )
        assertEquals("ok", reply.text)
    }

    @Test
    fun `a blank standing instruction is left out of the body entirely`() {
        val sent = body(AiRequest(connectionId = testConnection, prompt = "hello"))
        assertFalse(sent.contains("systemInstruction"))
        assertTrue(sent.contains("hello"))
    }

    @Test
    fun `a standing instruction that was given is sent as its own field`() {
        val sent = body(
            AiRequest(connectionId = testConnection, prompt = "hello", systemInstruction = "Answer in German"),
        )
        assertTrue(sent.contains("systemInstruction"))
        assertTrue(sent.contains("Answer in German"))
    }

    /**
     * The table has been broken twice by Google and once would have been enough:
     * an override is what turns "wait for an app update" into a text field.
     */
    @Test
    fun `a model named on the connection wins over the built-in table`() {
        val overridden = connection.copy(balancedModel = "gemini-9-something")
        // Gemini carries the model in the URL rather than the body, so that is where
        // the override has to land.
        assertTrue(
            GeminiProtocol.endpoint(overridden, AiModel.BALANCED).contains("gemini-9-something"),
        )
    }

    @Test
    fun `a tier nobody overrode still comes off the table`() {
        val overridden = connection.copy(balancedModel = "gemini-9-something")
        assertTrue(
            GeminiProtocol.endpoint(overridden, AiModel.FAST)
                .contains(GeminiProtocol.modelId(AiModel.FAST)),
        )
    }

    /**
     * Gemini 3 replaced `thinkingBudget` with `thinkingLevel`, and **sending both
     * is a 400** rather than a preference the server picks between. This is the
     * regression test for exactly that: the legacy field must not reappear beside
     * the new one when somebody edits this table.
     */
    @Test
    fun `the thinking level is sent and the legacy budget field never is`() {
        for (model in AiModel.entries) {
            val sent = body(request(model))
            assertTrue("$model sends no thinking level", sent.contains("\"thinkingLevel\""))
            assertFalse("$model still sends the legacy budget field", sent.contains("thinkingBudget"))
        }
    }

    /**
     * The trap this integration exists around: thinking tokens are drawn from the
     * same budget as the reply, so every tier gets headroom *added* to the user's
     * limit. Sending the bare limit is what makes a thinking model answer nothing
     * at all — and since Gemini 3 cannot turn thinking off, that now applies to
     * the fast tier too, where it once did not.
     */
    @Test
    fun `every tier gets thinking headroom on top of the requested reply length`() {
        for (model in AiModel.entries) {
            assertTrue(
                "$model must leave room to think beyond the reply limit, or it can answer nothing",
                headroomOf(model) > 0,
            )
        }
    }

    /** More thinking asked for means more room to do it in. */
    @Test
    fun `a more thorough tier gets more headroom than a faster one`() {
        assertTrue(headroomOf(AiModel.FAST) < headroomOf(AiModel.BALANCED))
        assertTrue(headroomOf(AiModel.BALANCED) < headroomOf(AiModel.THOROUGH))
    }

    @Test
    fun `a zero reply limit still asks for at least one token beyond the headroom`() {
        val zero = body(request(AiModel.FAST, maxOutputTokens = 0))
        val one = body(request(AiModel.FAST, maxOutputTokens = 1))
        assertEquals(
            Regex("\"maxOutputTokens\":(\\d+)").find(one)!!.groupValues[1],
            Regex("\"maxOutputTokens\":(\\d+)").find(zero)!!.groupValues[1],
        )
    }

    /**
     * A `-preview` id is one Google withdraws on its own schedule, and a 2.5-family
     * id stopped being offered to *new* keys while going on working for old ones —
     * a failure that only ever appears on somebody else's phone. Both have already
     * happened to this table once.
     */
    @Test
    fun `no tier names a preview or a superseded model`() {
        for (model in AiModel.entries) {
            val id = GeminiProtocol.modelId(model)
            assertFalse("$model names a preview id, which gets withdrawn", id.contains("preview"))
            assertFalse("$model names a 2.x model, which new keys are refused", id.startsWith("gemini-2"))
        }
    }

    /**
     * Every tier must reach a real endpoint. The point of [AiModel] naming a
     * trade-off rather than a product is that this mapping can change without
     * touching a saved workflow — this only pins that none of them is blank.
     */
    @Test
    fun `every model tier maps to a model id and an endpoint`() {
        for (model in AiModel.entries) {
            val id = GeminiProtocol.modelId(model)
            assertTrue("$model has no model id", id.isNotBlank())
            val endpoint = GeminiProtocol.endpoint(connection, model)
            assertTrue(endpoint.startsWith("https://"))
            assertTrue(endpoint.endsWith(":generateContent"))
            assertTrue(endpoint.contains(id))
        }
    }
}
