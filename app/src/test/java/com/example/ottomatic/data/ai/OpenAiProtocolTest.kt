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
 * The chat-completions format, and the three providers that share it.
 *
 * Most of what is pinned here is the *differences*, because the shape being identical
 * is exactly what makes a difference easy to lose: the token field name, whether
 * `reasoning_effort` is sent, and which of them can default a model at all. Getting
 * the first of those wrong is the worst failure in this file — a server that does not
 * recognise the bound simply ignores it, which is an unbounded answer rather than an
 * error.
 */
class OpenAiProtocolTest {

    private fun connection(
        provider: AiProvider,
        baseUrl: String = "",
        fastModel: String = "",
        balancedModel: String = "",
    ) = AiConnection(
        id = "connection-id",
        name = provider.name,
        provider = provider,
        baseUrl = baseUrl,
        fastModel = fastModel,
        balancedModel = balancedModel,
    )

    private val openAi = connection(AiProvider.OPENAI)
    private val openRouter = connection(AiProvider.OPENROUTER, fastModel = "meta-llama/llama-4")
    private val selfHosted = connection(
        AiProvider.OPENAI_COMPATIBLE,
        baseUrl = "http://192.168.1.10:8000/v1",
        fastModel = "Qwen/Qwen3-8B",
    )

    private fun request(
        model: AiModel = AiModel.FAST,
        maxOutputTokens: Int = 100,
        systemInstruction: String = "",
    ) = AiRequest(
        connectionId = "connection-id",
        prompt = "hi",
        model = model,
        maxOutputTokens = maxOutputTokens,
        systemInstruction = systemInstruction,
    )

    // ---- what a response means -------------------------------------------------

    @Test
    fun `a plain answer is read off the first choice`() {
        val reply = OpenAiProtocol.OpenAi.readReply(
            status = 200,
            body = """
                {"choices":[{"message":{"role":"assistant","content":"Battery is low"},"finish_reason":"stop"}]}
            """.trimIndent(),
        )
        assertEquals("Battery is low", reply.text)
        assertEquals("", reply.error)
        assertFalse(reply.truncated)
    }

    @Test
    fun `an answer stopped at the token bound is returned and flagged`() {
        val reply = OpenAiProtocol.OpenAi.readReply(
            status = 200,
            body = """
                {"choices":[{"message":{"content":"It was the best of"},"finish_reason":"length"}]}
            """.trimIndent(),
        )
        assertEquals("It was the best of", reply.text)
        assertTrue(reply.truncated)
    }

    @Test
    fun `a choice with no text is a failure naming the limit`() {
        val reply = OpenAiProtocol.OpenAi.readReply(
            status = 200,
            body = """{"choices":[{"message":{"content":""},"finish_reason":"length"}]}""",
        )
        assertEquals("", reply.text)
        assertTrue(reply.error.contains("cut off"))
    }

    @Test
    fun `a content filter says so rather than reading as an empty success`() {
        val reply = OpenAiProtocol.OpenAi.readReply(
            status = 200,
            body = """{"choices":[{"message":{"content":null},"finish_reason":"content_filter"}]}""",
        )
        assertTrue(reply.error.contains("filter"))
    }

    @Test
    fun `a refused key reports the server's own sentence rather than the status`() {
        val reply = OpenAiProtocol.OpenAi.readReply(
            status = 401,
            body = """{"error":{"message":"Incorrect API key provided.","type":"invalid_request_error"}}""",
        )
        assertTrue(reply.error.contains("Incorrect API key provided."))
        assertTrue(reply.error.contains("401"))
    }

    /** The commonest self-hosted mistake there is, and one only the server can name. */
    @Test
    fun `an unknown model reports the server's own sentence`() {
        val reply = OpenAiProtocol.SelfHosted.readReply(
            status = 404,
            body = """{"error":{"message":"The model `llama-3` does not exist","code":"model_not_found"}}""",
        )
        assertTrue(reply.error.contains("does not exist"))
    }

    @Test
    fun `an exhausted quota is reported in the server's words too`() {
        val reply = OpenAiProtocol.OpenAi.readReply(
            status = 429,
            body = """{"error":{"message":"You exceeded your current quota.","type":"insufficient_quota"}}""",
        )
        assertTrue(reply.error.contains("exceeded your current quota"))
    }

    /**
     * A self-hosted server that is switched off, or an address with a typo in it.
     * The wording names the address as well as the connection, because unlike a
     * hosted provider the address is a thing the user can have got wrong.
     */
    @Test
    fun `no response at all names the address as well as the connection`() {
        val reply = OpenAiProtocol.SelfHosted.readReply(status = NO_RESPONSE, body = "")
        assertTrue(reply.error.contains("address"))
        assertFalse(reply.error.contains("HTTP"))
    }

    @Test
    fun `an unknown field in the envelope does not stop the answer being read`() {
        val reply = OpenAiProtocol.SelfHosted.readReply(
            status = 200,
            body = """
                {"choices":[{"message":{"content":"ok","reasoning_content":"…"},"finish_reason":"stop",
                 "logprobs":null}],"something_new":7}
            """.trimIndent(),
        )
        assertEquals("ok", reply.text)
    }

    // ---- what the three providers do differently -------------------------------

    /**
     * **The fork that actually bites.** OpenAI rejects the old name; vLLM, Ollama and
     * llama.cpp ignore the new one — and an ignored bound is an unbounded answer
     * rather than an error, which is the worse of the two failures because nothing
     * reports it.
     */
    @Test
    fun `OpenAI is asked with max_completion_tokens and everything else with max_tokens`() {
        val toOpenAi = OpenAiProtocol.OpenAi.requestBody(request(), openAi)
        assertTrue(toOpenAi.contains("\"max_completion_tokens\""))
        assertFalse(toOpenAi.contains("\"max_tokens\""))

        for ((protocol, on) in listOf(
            OpenAiProtocol.OpenRouter to openRouter,
            OpenAiProtocol.SelfHosted to selfHosted,
        )) {
            val body = protocol.requestBody(request(), on)
            assertTrue("${on.provider} must be asked with max_tokens", body.contains("\"max_tokens\""))
            assertFalse(body.contains("\"max_completion_tokens\""))
        }
    }

    /**
     * Sent only where it is known to be read: an OpenAI-compatible server that does
     * not recognise it usually 400s on the unknown field rather than ignoring it,
     * which would break every self-hosted connection for a parameter they cannot use.
     */
    @Test
    fun `reasoning effort is sent to OpenAI and to nobody else`() {
        assertTrue(OpenAiProtocol.OpenAi.requestBody(request(), openAi).contains("reasoning_effort"))
        assertFalse(
            OpenAiProtocol.OpenRouter.requestBody(request(), openRouter).contains("reasoning_effort"),
        )
        assertFalse(
            OpenAiProtocol.SelfHosted.requestBody(request(), selfHosted).contains("reasoning_effort"),
        )
    }

    @Test
    fun `each provider posts to its own chat completions endpoint`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            OpenAiProtocol.OpenAi.endpoint(openAi, AiModel.FAST),
        )
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            OpenAiProtocol.OpenRouter.endpoint(openRouter, AiModel.FAST),
        )
        assertEquals(
            "http://192.168.1.10:8000/v1/chat/completions",
            OpenAiProtocol.SelfHosted.endpoint(selfHosted, AiModel.FAST),
        )
    }

    @Test
    fun `a base url on the connection overrides the provider's own`() {
        val proxied = openAi.copy(baseUrl = "https://proxy.example.com/v1")
        assertEquals(
            "https://proxy.example.com/v1/chat/completions",
            OpenAiProtocol.OpenAi.endpoint(proxied, AiModel.FAST),
        )
    }

    @Test
    fun `OpenRouter identifies the app so spend is attributable in the dashboard`() {
        assertEquals("Ottomatic", OpenAiProtocol.OpenRouter.headers("k")["X-Title"])
        assertEquals("Bearer k", OpenAiProtocol.OpenRouter.headers("k")["Authorization"])
    }

    // ---- model ids -------------------------------------------------------------

    @Test
    fun `OpenAI defaults every tier so a connection works with nothing else filled in`() {
        for (model in AiModel.entries) {
            assertTrue("$model has no default", OpenAiProtocol.OpenAi.defaultModelId(model).isNotBlank())
        }
    }

    /**
     * Deliberately no table: OpenRouter serves hundreds of models from dozens of
     * vendors and a self-hosted server serves whatever it was started with, so any
     * three ids picked here would be an invented opinion waiting to be retired.
     */
    @Test
    fun `the open-ended providers default nothing at all`() {
        for (model in AiModel.entries) {
            assertEquals("", OpenAiProtocol.OpenRouter.defaultModelId(model))
            assertEquals("", OpenAiProtocol.SelfHosted.defaultModelId(model))
        }
    }

    /**
     * The dominant self-hosted setup is one machine serving one model. Naming it
     * once and having every tier use it is what somebody means; demanding three
     * copies of the same string is a form filled in for nothing.
     */
    @Test
    fun `a tier left blank falls back to the fast model where there is no table`() {
        val body = OpenAiProtocol.SelfHosted.requestBody(request(AiModel.THOROUGH), selfHosted)
        assertTrue(body.contains("Qwen/Qwen3-8B"))
    }

    @Test
    fun `a tier that was filled in is used rather than the fast model`() {
        val both = selfHosted.copy(balancedModel = "Qwen/Qwen3-32B")
        val body = OpenAiProtocol.SelfHosted.requestBody(request(AiModel.BALANCED), both)
        assertTrue(body.contains("Qwen/Qwen3-32B"))
        assertFalse(body.contains("Qwen3-8B"))
    }

    @Test
    fun `a model named on the connection wins over OpenAI's table`() {
        val overridden = openAi.copy(fastModel = "gpt-something-new")
        val body = OpenAiProtocol.OpenAi.requestBody(request(AiModel.FAST), overridden)
        assertTrue(body.contains("gpt-something-new"))
    }

    // ---- the guards that keep a blank field off the network --------------------

    /**
     * Without this the failure arrives as a `MalformedURLException` swallowed into
     * [NO_RESPONSE] and worded "check the phone's connection", which sends somebody
     * to look at their Wi-Fi over a blank box in this app.
     */
    @Test
    fun `a self-hosted connection with no address is refused before the network`() {
        val problem = OpenAiProtocol.SelfHosted.configurationProblem(
            connection(AiProvider.OPENAI_COMPATIBLE, fastModel = "x"),
            AiModel.FAST,
        )
        assertTrue(problem.orEmpty().contains("server address"))
    }

    @Test
    fun `a connection with no model named is refused before the network`() {
        val problem = OpenAiProtocol.OpenRouter.configurationProblem(
            connection(AiProvider.OPENROUTER),
            AiModel.FAST,
        )
        assertTrue(problem.orEmpty().contains("model"))
    }

    @Test
    fun `a fully configured connection has nothing to complain about`() {
        assertEquals(null, OpenAiProtocol.SelfHosted.configurationProblem(selfHosted, AiModel.THOROUGH))
        assertEquals(null, OpenAiProtocol.OpenAi.configurationProblem(openAi, AiModel.FAST))
    }

    // ---- listing ---------------------------------------------------------------

    @Test
    fun `a listing is read as its ids`() {
        val models = OpenAiProtocol.SelfHosted.readModels(
            status = 200,
            body = """{"object":"list","data":[{"id":"Qwen/Qwen3-8B","object":"model"}]}""",
        )
        assertEquals(listOf("Qwen/Qwen3-8B"), models.ids)
        assertEquals("", models.error)
    }

    @Test
    fun `a listing that failed carries the reason rather than an empty list`() {
        val models = OpenAiProtocol.SelfHosted.readModels(status = 404, body = "not found")
        assertTrue(models.ids.isEmpty())
        assertTrue(models.error.isNotBlank())
    }

    // ---- the standing instruction ----------------------------------------------

    @Test
    fun `a blank standing instruction sends no system turn at all`() {
        val body = OpenAiProtocol.OpenAi.requestBody(request(), openAi)
        assertFalse(body.contains("\"system\""))
    }

    @Test
    fun `a standing instruction that was given leads the message array`() {
        val body = OpenAiProtocol.OpenAi.requestBody(request(systemInstruction = "In German"), openAi)
        assertTrue(body.indexOf("\"system\"") < body.indexOf("\"user\""))
        assertTrue(body.contains("In German"))
    }
}
