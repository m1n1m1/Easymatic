package com.example.ottomatic.data.ai

import com.example.ottomatic.core.service.AiModel
import com.example.ottomatic.core.service.AiReply
import com.example.ottomatic.core.service.AiRequest
import com.example.ottomatic.core.service.AiTool
import com.example.ottomatic.core.service.AiToolCall
import com.example.ottomatic.domain.model.AiConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * OpenAI's chat-completions format — and, because everybody copied it, most of the
 * rest of the industry's.
 *
 * **One class serving three providers is the whole argument for not having five.**
 * OpenAI, OpenRouter and every self-hosted server worth naming — vLLM, Ollama, LM
 * Studio, llama.cpp, LocalAI, TGI — speak `POST {base}/chat/completions` with a
 * bearer token and answer with `choices[0].message.content`. So do Groq, DeepSeek,
 * Mistral and xAI. They differ in a default host, a token field name and whether one
 * optional parameter is understood; that is a constructor argument each, not a
 * parallel file each.
 *
 * The subclasses are objects rather than data, so [protocolFor] can hand one back
 * without allocating and a `when` over [com.example.ottomatic.domain.model.AiProvider]
 * stays exhaustive.
 */
@Suppress("TooManyFunctions") // One member per wire concern, plus one per provider difference.
internal sealed class OpenAiProtocol(
    /** Where requests go when the connection names no base URL. Blank means it must. */
    private val defaultBaseUrl: String,
    /**
     * What this server calls the output bound.
     *
     * **This is the fork that actually bites.** OpenAI deprecated `max_tokens` for
     * reasoning models in favour of `max_completion_tokens` and rejects the old name;
     * vLLM, Ollama and llama.cpp's server understand only `max_tokens` and ignore the
     * new one — which is worse than an error, because an ignored bound means an
     * unbounded answer billed or generated in full. So the name is per provider and
     * pinned by a test.
     */
    private val tokenField: String,
    /**
     * Whether `reasoning_effort` is understood here.
     *
     * [GeminiProtocol]'s `thinkingLevel` by another name, and on OpenAI it is most
     * of what separates the balanced tier from the thorough one. Sent only where it
     * is known to be read: an OpenAI-compatible server that does not recognise it
     * usually 400s on the unknown field rather than ignoring it, which would break
     * every self-hosted connection for a parameter they cannot use.
     */
    private val sendsReasoningEffort: Boolean,
    /** Anything beyond auth and content type. */
    private val extraHeaders: Map<String, String> = emptyMap(),
) : AiProtocol {

    /** This provider's own id for [model], or blank when it publishes no table. */
    abstract fun defaultModelId(model: AiModel): String

    /**
     * The id actually sent: what the profile names, else this provider's own table.
     *
     * **The cross-tier fallback that used to live here is gone, and profiles are why.**
     * It existed because a connection carried three model-id fields and the dominant
     * self-hosted setup is one machine serving one model — so a blank Balanced field
     * borrowed the Fast one rather than failing. A profile names exactly one model, so
     * "one machine, one model" is now one profile, and there is no second field for a
     * blank one to borrow from. Where a provider has a table, that table still wins.
     */
    private fun resolvedModelId(target: AiTarget): String =
        modelIdFor(target, defaultModelId(target.effort))

    private fun base(connection: AiConnection): String = baseUrlFor(connection, defaultBaseUrl)

    override fun endpoint(target: AiTarget): String = "${base(target.connection)}/chat/completions"

    override fun modelsEndpoint(connection: AiConnection): String = "${base(connection)}/models"

    override fun headers(key: String): Map<String, String> = buildMap {
        put(CONTENT_TYPE_HEADER, JSON_CONTENT_TYPE)
        put("Authorization", "Bearer $key")
        putAll(extraHeaders)
    }

    /**
     * What is missing before this connection can be used at all.
     *
     * Answered here rather than left to the transport because the failure otherwise
     * arrives as a `MalformedURLException` swallowed into [NO_RESPONSE] and worded
     * "check the phone's connection" — which sends somebody to look at their Wi-Fi
     * over a blank field in this app. The same rule the editor and the Problems
     * panel apply, read off `AiConnection.isConfigured`'s two halves.
     */
    override fun configurationProblem(target: AiTarget): String? = when {
        base(target.connection).isBlank() ->
            "\"${target.connection.name}\" has no server address — open AI settings and add one"
        resolvedModelId(target).isBlank() ->
            "\"${target.profile.name}\" has no model chosen — open AI settings and name one"
        else -> null
    }

    /**
     * The request body for [request].
     *
     * The standing instruction is a `system` message at the head of the array,
     * omitted entirely when blank rather than sent empty — an absent turn and an
     * empty one are different requests, and several servers refuse the second.
     */
    override fun requestBody(request: AiRequest, target: AiTarget): String = buildJsonObject {
        put(MODEL_KEY, resolvedModelId(target))
        // No thinking headroom is added here, unlike Gemini and Anthropic: on this
        // API reasoning tokens are counted separately from the reply on the servers
        // that produce them at all, so the user's limit means the reply.
        put(tokenField, request.maxOutputTokens.coerceAtLeast(1))
        if (sendsReasoningEffort) put(EFFORT_KEY, reasoningEffort(target.effort))
        putJsonArray(MESSAGES_KEY) {
            if (request.systemInstruction.isNotBlank()) {
                add(message(SYSTEM_ROLE, request.systemInstruction))
            }
            add(userTurn(request))
        }
    }.toString()

    /**
     * The user turn: a plain string, or a content array once it carries a picture.
     *
     * Kept as a string in the ordinary case rather than always sending the array
     * form, so a request with no images renders exactly the body it did before — which
     * matters more here than for the other two, since several self-hosted servers
     * accept only the string shape.
     */
    private fun userTurn(request: AiRequest): JsonObject = buildJsonObject {
        put(ROLE_KEY, USER_ROLE)
        if (request.images.isEmpty()) {
            put(CONTENT_KEY, request.prompt)
            return@buildJsonObject
        }
        putJsonArray(CONTENT_KEY) {
            request.images.forEach { image ->
                add(
                    buildJsonObject {
                        put(TYPE_KEY, IMAGE_URL_TYPE)
                        // This API takes a `data:` URI rather than the two separate
                        // fields the other two want.
                        putJsonObject(IMAGE_URL_KEY) {
                            put(URL_KEY, "data:${image.mediaType};base64,${image.base64}")
                        }
                    },
                )
            }
            add(buildJsonObject { put(TYPE_KEY, TEXT_TYPE); put(TEXT_KEY, request.prompt) })
        }
    }

    /**
     * The request body for an exchange that may use [tools].
     *
     * The `tool` role is this API's alone — Anthropic puts a result inside a `user`
     * turn and Gemini inside a `functionResponse` part — and it is addressed by the
     * `tool_call_id` the server minted, so nothing here can be matched by name.
     *
     * The assistant turn is echoed as the whole `message` object it arrived as,
     * rather than rebuilt from its parts: it carries `tool_calls` in the exact shape
     * the server expects back, and reconstructing that is a way to get it subtly
     * wrong for no gain.
     */
    override fun conversationBody(
        exchange: List<AiExchange>,
        tools: List<AiTool>,
        request: AiRequest,
        target: AiTarget,
    ): String = buildJsonObject {
        put(MODEL_KEY, resolvedModelId(target))
        put(tokenField, request.maxOutputTokens.coerceAtLeast(1))
        if (sendsReasoningEffort) put(EFFORT_KEY, reasoningEffort(target.effort))
        if (tools.isNotEmpty()) putJsonArray(TOOLS_KEY) { tools.forEach { add(declare(it)) } }
        putJsonArray(MESSAGES_KEY) {
            if (request.systemInstruction.isNotBlank()) {
                add(message(SYSTEM_ROLE, request.systemInstruction))
            }
            exchange.forEach { appendTurn(it) }
        }
    }.toString()

    /** One tool, wrapped in this API's `function` envelope. */
    private fun declare(tool: AiTool): JsonObject = buildJsonObject {
        put(TYPE_KEY, FUNCTION_TYPE)
        putJsonObject(FUNCTION_KEY) {
            put(NAME_KEY, tool.name)
            put(DESCRIPTION_KEY, tool.description)
            put(PARAMETERS_KEY, toolParameterSchema(tool.parameters))
        }
    }

    /** One thing that happened, as the one or more messages this API expects for it. */
    private fun JsonArrayBuilder.appendTurn(entry: AiExchange) {
        when (entry) {
            is AiExchange.Ask -> add(message(USER_ROLE, entry.prompt))
            is AiExchange.Said -> entry.turn.raw?.let { add(it) }
            is AiExchange.Ran -> entry.results.forEach { (call, result) ->
                add(
                    buildJsonObject {
                        put(ROLE_KEY, TOOL_ROLE)
                        put(TOOL_CALL_ID_KEY, call.id)
                        put(CONTENT_KEY, result.text)
                    },
                )
            }
        }
    }

    /**
     * What [body] means when tools were offered.
     *
     * `content` is **null** on a tool-calling turn here, not merely empty — which is
     * why [readReply]'s correct "an empty answer is a failure" rule has to be
     * conditional in this path rather than reused.
     */
    @Suppress("ReturnCount") // Each exit names a distinct outcome, as in `readReply`.
    override fun readTurn(status: Int, body: String): AiTurn {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        if (status !in SUCCESS_RANGE) return AiTurn.failed(errorText(status, root))
        root ?: return AiTurn.failed("The model returned something unreadable (HTTP $status)")
        val choice = root[CHOICES_KEY]?.arrayOrNull()?.firstOrNull()?.objectOrNull()
            ?: return AiTurn.failed("The model returned no answer")
        val message = choice[CHOICE_MESSAGE_KEY]?.objectOrNull()
            ?: return AiTurn.failed("The model returned no answer")
        val text = message[CONTENT_KEY]?.stringOrNull().orEmpty()
        val calls = toolCallsIn(message)
        val finish = choice[FINISH_KEY]?.stringOrNull().orEmpty()
        if (calls.isEmpty() && text.isBlank()) return AiTurn.failed(emptyAnswerText(finish))
        return AiTurn(
            text = text,
            toolCalls = calls,
            raw = message,
            truncated = finish == LENGTH_FINISH,
        )
    }

    /**
     * The turn's tool calls.
     *
     * `function.arguments` is a **JSON string** on this API rather than an object —
     * the one place the three providers disagree about more than a key name — so it
     * is parsed before being read. A model that emits malformed JSON there yields no
     * arguments rather than failing the turn, and the node then decodes the config
     * defaults, which is the same degradation a blank config field already has.
     */
    private fun toolCallsIn(message: JsonObject): List<AiToolCall> =
        message[TOOL_CALLS_KEY]?.arrayOrNull().orEmpty().mapNotNull { entry ->
            val call = entry.objectOrNull() ?: return@mapNotNull null
            val function = call[FUNCTION_KEY]?.objectOrNull() ?: return@mapNotNull null
            val name = function[NAME_KEY]?.stringOrNull() ?: return@mapNotNull null
            val raw = function[ARGUMENTS_KEY]?.stringOrNull().orEmpty()
            AiToolCall(
                id = call[ID_KEY]?.stringOrNull().orEmpty(),
                name = name,
                arguments = runCatching { json.parseToJsonElement(raw).jsonObject }
                    .getOrNull()?.asToolArguments().orEmpty(),
            )
        }

    /**
     * What [body] means, given the [status] it arrived with.
     *
     * `error.message` before anything else, for [GeminiProtocol]'s reason — "You
     * exceeded your current quota" and "model `llama-3` does not exist" are both
     * sentences somebody can act on, and the second is the commonest self-hosted
     * mistake there is.
     */
    @Suppress("ReturnCount") // Each exit names a distinct outcome; folding them is what loses the diagnosis.
    override fun readReply(status: Int, body: String): AiReply {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        if (status !in SUCCESS_RANGE) return AiReply(error = errorText(status, root))
        root ?: return AiReply(error = "The model returned something unreadable (HTTP $status)")
        val choice = root[CHOICES_KEY]?.arrayOrNull()?.firstOrNull()?.objectOrNull()
            ?: return AiReply(error = "The model returned no answer")
        val text = choice[CHOICE_MESSAGE_KEY]?.objectOrNull()?.get(CONTENT_KEY)?.stringOrNull().orEmpty()
        val finish = choice[FINISH_KEY]?.stringOrNull().orEmpty()
        // An empty answer is a failure rather than an empty success, as it is for
        // every other provider here: a node pulsing on with "" is indistinguishable
        // from a prompt answered with silence.
        if (text.isBlank()) return AiReply(error = emptyAnswerText(finish))
        return AiReply(text = text, truncated = finish == LENGTH_FINISH)
    }

    /** The listing, as `data[].id` — served by OpenAI, OpenRouter, vLLM, Ollama and LM Studio alike. */
    override fun readModels(status: Int, body: String): AiModels {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        if (status !in SUCCESS_RANGE) return AiModels(error = errorText(status, root))
        val ids = root?.get(DATA_KEY)?.arrayOrNull().orEmpty()
            .mapNotNull { it.objectOrNull()?.get(ID_KEY)?.stringOrNull() }
        return AiModels(ids = ids)
    }

    private fun reasoningEffort(model: AiModel): String = when (model) {
        AiModel.FAST -> "low"
        AiModel.BALANCED -> "medium"
        AiModel.THOROUGH -> "high"
    }

    private fun message(role: String, content: String) = buildJsonObject {
        put(ROLE_KEY, role)
        put(CONTENT_KEY, content)
    }

    /** A choice that came back with no text in it, named by why it stopped. */
    private fun emptyAnswerText(finish: String): String = when (finish) {
        LENGTH_FINISH -> "The reply was cut off before any text was produced — raise the reply limit"
        FILTER_FINISH -> "The prompt or the answer was blocked by the provider's content filter"
        "", STOP_FINISH -> "The model returned an empty answer"
        else -> "The model stopped without answering ($finish)"
    }

    private fun errorText(status: Int, root: JsonObject?): String =
        errorMessage(root)?.let { "$it (HTTP $status)" } ?: httpOnlyText(status)

    private fun httpOnlyText(status: Int): String = when (status) {
        NO_RESPONSE -> "Could not reach the model — check the address and the phone's connection"
        else -> "The model refused the request (HTTP $status)"
    }

    /**
     * OpenAI proper.
     *
     * The one of the three with a tier table, and the one whose ids will churn —
     * which is exactly what the per-connection override exists for. The balanced and
     * thorough tiers name the same model and differ by `reasoning_effort`, following
     * the API's own design rather than inventing a third product.
     */
    object OpenAi : OpenAiProtocol(
        defaultBaseUrl = "https://api.openai.com/v1",
        tokenField = "max_completion_tokens",
        sendsReasoningEffort = true,
    ) {
        override fun defaultModelId(model: AiModel): String = when (model) {
            AiModel.FAST -> "gpt-5.1-mini"
            AiModel.BALANCED -> "gpt-5.1"
            AiModel.THOROUGH -> "gpt-5.1"
        }
    }

    /**
     * OpenRouter — one key in front of every provider's catalogue.
     *
     * **No tier table on purpose.** It serves hundreds of models from dozens of
     * vendors and no three of them are the obvious fast/balanced/thorough; picking
     * three would be inventing an opinion and then breaking it on somebody else's
     * deprecation schedule. The model id is required and chosen from the live
     * listing instead.
     *
     * `X-Title` is what OpenRouter shows in the user's own usage dashboard. Sending
     * it costs nothing and turns a page of unlabelled spend into a line that says
     * where it went.
     */
    object OpenRouter : OpenAiProtocol(
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        tokenField = "max_tokens",
        sendsReasoningEffort = false,
        extraHeaders = mapOf("X-Title" to "Ottomatic"),
    ) {
        override fun defaultModelId(model: AiModel): String = ""
    }

    /**
     * Anything else speaking this API — a model server on the user's own network, or
     * a hosted provider that copied the format.
     *
     * Neither a base URL nor a model id can be defaulted: the first is a machine only
     * the user knows about, and the second is whatever that machine was started with.
     * Both are required, and [configurationProblem] says which is missing rather than
     * letting the request fail as a network error.
     */
    object SelfHosted : OpenAiProtocol(
        defaultBaseUrl = "",
        tokenField = "max_tokens",
        sendsReasoningEffort = false,
    ) {
        override fun defaultModelId(model: AiModel): String = ""
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        const val LENGTH_FINISH = "length"
        const val STOP_FINISH = "stop"
        const val FILTER_FINISH = "content_filter"
        const val SYSTEM_ROLE = "system"
        const val USER_ROLE = "user"
        const val TOOL_ROLE = "tool"
        const val FUNCTION_TYPE = "function"
        const val TOOLS_KEY = "tools"
        const val TOOL_CALLS_KEY = "tool_calls"
        const val TOOL_CALL_ID_KEY = "tool_call_id"
        const val FUNCTION_KEY = "function"
        const val ARGUMENTS_KEY = "arguments"
        const val PARAMETERS_KEY = "parameters"
        const val NAME_KEY = "name"
        const val DESCRIPTION_KEY = "description"
        const val TYPE_KEY = "type"
        const val TEXT_TYPE = "text"
        const val TEXT_KEY = "text"
        const val IMAGE_URL_TYPE = "image_url"
        const val IMAGE_URL_KEY = "image_url"
        const val URL_KEY = "url"
        const val MODEL_KEY = "model"
        const val EFFORT_KEY = "reasoning_effort"
        const val MESSAGES_KEY = "messages"
        const val ROLE_KEY = "role"
        const val CONTENT_KEY = "content"
        const val CHOICES_KEY = "choices"

        /** Named apart from the file-level `MESSAGE_KEY`, which is `error.message`. */
        const val CHOICE_MESSAGE_KEY = "message"
        const val FINISH_KEY = "finish_reason"
        const val DATA_KEY = "data"
        const val ID_KEY = "id"
    }
}
