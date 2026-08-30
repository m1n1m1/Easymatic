package io.github.m1n1m1.easymatic.data.ai

import io.github.m1n1m1.easymatic.core.service.AiModel
import io.github.m1n1m1.easymatic.core.service.AiRequest
import io.github.m1n1m1.easymatic.domain.model.AiConnection
import io.github.m1n1m1.easymatic.domain.model.AiModality
import io.github.m1n1m1.easymatic.domain.model.AiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private fun connection(provider: AiProvider, baseUrl: String = "") = AiConnection(
        id = "connection-id",
        name = provider.name,
        provider = provider,
        baseUrl = baseUrl,
    )

    private val openAi = connection(AiProvider.OPENAI)
    private val openRouter = connection(AiProvider.OPENROUTER)
    private val selfHosted = connection(
        AiProvider.OPENAI_COMPATIBLE,
        baseUrl = "http://192.168.1.10:8000/v1",
    )

    /** The account paired with a profile naming a model, which is what a request needs. */
    private val openAiTarget = target(openAi)
    private val openRouterTarget = target(openRouter, modelId = "meta-llama/llama-4")
    private val selfHostedTarget = target(selfHosted, modelId = "Qwen/Qwen3-8B")

    private fun request(
        maxOutputTokens: Int = 100,
        systemInstruction: String = "",
    ) = AiRequest(
        modelRef = TEST_MODEL_REF,
        prompt = "hi",
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
        val toOpenAi = OpenAiProtocol.OpenAi.requestBody(request(), openAiTarget)
        assertTrue(toOpenAi.contains("\"max_completion_tokens\""))
        assertFalse(toOpenAi.contains("\"max_tokens\""))

        for ((protocol, on) in listOf(
            OpenAiProtocol.OpenRouter to openRouterTarget,
            OpenAiProtocol.SelfHosted to selfHostedTarget,
        )) {
            val body = protocol.requestBody(request(), on)
            val provider = on.connection.provider
            assertTrue("$provider must be asked with max_tokens", body.contains("\"max_tokens\""))
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
        assertTrue(OpenAiProtocol.OpenAi.requestBody(request(), openAiTarget).contains("reasoning_effort"))
        assertFalse(
            OpenAiProtocol.OpenRouter.requestBody(request(), openRouterTarget).contains("reasoning_effort"),
        )
        assertFalse(
            OpenAiProtocol.SelfHosted.requestBody(request(), selfHostedTarget).contains("reasoning_effort"),
        )
    }

    @Test
    fun `each provider posts to its own chat completions endpoint`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            OpenAiProtocol.OpenAi.endpoint(openAiTarget),
        )
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            OpenAiProtocol.OpenRouter.endpoint(openRouterTarget),
        )
        assertEquals(
            "http://192.168.1.10:8000/v1/chat/completions",
            OpenAiProtocol.SelfHosted.endpoint(selfHostedTarget),
        )
    }

    @Test
    fun `a base url on the connection overrides the provider's own`() {
        val proxied = openAi.copy(baseUrl = "https://proxy.example.com/v1")
        assertEquals(
            "https://proxy.example.com/v1/chat/completions",
            OpenAiProtocol.OpenAi.endpoint(target(proxied)),
        )
    }

    @Test
    fun `OpenRouter identifies the app so spend is attributable in the dashboard`() {
        assertEquals("Easymatic", OpenAiProtocol.OpenRouter.headers("k")["X-Title"])
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
     * The one machine serving one model is now one *profile* naming it, so its effort
     * is free to be whatever the user set — there is no second field for a blank one
     * to borrow from, which is what the cross-tier fallback used to exist for.
     */
    @Test
    fun `the profile's model is sent whatever effort it asks for`() {
        val body = OpenAiProtocol.SelfHosted.requestBody(
            request(),
            target(selfHosted, AiModel.THOROUGH, modelId = "Qwen/Qwen3-8B"),
        )
        assertTrue(body.contains("Qwen/Qwen3-8B"))
    }

    @Test
    fun `two profiles on one account can name two different models`() {
        val big = OpenAiProtocol.SelfHosted.requestBody(
            request(),
            target(selfHosted, AiModel.BALANCED, modelId = "Qwen/Qwen3-32B"),
        )
        assertTrue(big.contains("Qwen/Qwen3-32B"))
        assertFalse(big.contains("Qwen3-8B"))
    }

    @Test
    fun `a model named on the profile wins over OpenAI's table`() {
        val body = OpenAiProtocol.OpenAi.requestBody(
            request(),
            target(openAi, modelId = "gpt-something-new"),
        )
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
            target(connection(AiProvider.OPENAI_COMPATIBLE), modelId = "x"),
        )
        assertTrue(problem.orEmpty().contains("server address"))
    }

    @Test
    fun `a profile with no model named is refused before the network`() {
        val problem = OpenAiProtocol.OpenRouter.configurationProblem(target(connection(AiProvider.OPENROUTER)))
        assertTrue(problem.orEmpty().contains("model"))
    }

    @Test
    fun `a fully configured profile has nothing to complain about`() {
        assertEquals(null, OpenAiProtocol.SelfHosted.configurationProblem(selfHostedTarget))
        assertEquals(null, OpenAiProtocol.OpenAi.configurationProblem(openAiTarget))
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

    /**
     * OpenRouter is the only provider anywhere in this app that publishes what each model
     * accepts, which is the entire reason the chooser can filter at all.
     */
    @Test
    fun `openrouter's published input modalities are read`() {
        val models = OpenAiProtocol.OpenRouter.readModels(
            status = 200,
            body = """
                {"data":[{"id":"google/gemini-3.6-flash","name":"Google: Gemini 3.6 Flash",
                "architecture":{"input_modalities":["text","image","audio"],
                "output_modalities":["text"]}}]}
            """.trimIndent(),
        )

        val model = models.models.single()
        assertEquals("google/gemini-3.6-flash", model.id)
        assertEquals("Google: Gemini 3.6 Flash", model.label)
        assertEquals(setOf(AiModality.TEXT, AiModality.IMAGE, AiModality.AUDIO), model.modalities)
    }

    /**
     * A plain `/v1/models` answers ids and nothing else, and null has to mean "did not
     * say". Reading it as "accepts nothing" would hide every model on OpenAI and on every
     * self-hosted server the moment a filter chip was ticked.
     */
    @Test
    fun `a listing with no architecture block claims no modalities at all`() {
        val models = OpenAiProtocol.OpenAi.readModels(
            status = 200,
            body = """{"object":"list","data":[{"id":"gpt-5.1","object":"model","owned_by":"openai"}]}""",
        )

        assertNull(models.models.single().modalities)
    }

    /** An empty array has told us nothing either, and must not read as "accepts nothing". */
    @Test
    fun `an empty modality array is treated as no answer`() {
        val models = OpenAiProtocol.OpenRouter.readModels(
            status = 200,
            body = """{"data":[{"id":"m","architecture":{"input_modalities":[]}}]}""",
        )

        assertNull(models.models.single().modalities)
    }

    /** A sixth modality upstream should cost a chip, never the row. */
    @Test
    fun `an unrecognised modality is dropped rather than losing the model`() {
        val models = OpenAiProtocol.OpenRouter.readModels(
            status = 200,
            body = """{"data":[{"id":"m","architecture":{"input_modalities":["text","hologram"]}}]}""",
        )

        assertEquals(setOf(AiModality.TEXT), models.models.single().modalities)
    }

    // ---- the standing instruction ----------------------------------------------

    @Test
    fun `a blank standing instruction sends no system turn at all`() {
        val body = OpenAiProtocol.OpenAi.requestBody(request(), openAiTarget)
        assertFalse(body.contains("\"system\""))
    }

    @Test
    fun `a standing instruction that was given leads the message array`() {
        val body = OpenAiProtocol.OpenAi.requestBody(request(systemInstruction = "In German"), openAiTarget)
        assertTrue(body.indexOf("\"system\"") < body.indexOf("\"user\""))
        assertTrue(body.contains("In German"))
    }
}
