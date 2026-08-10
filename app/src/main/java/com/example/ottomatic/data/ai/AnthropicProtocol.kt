package com.example.ottomatic.data.ai

import com.example.ottomatic.core.service.AiModel
import com.example.ottomatic.core.service.AiReply
import com.example.ottomatic.core.service.AiRequest
import com.example.ottomatic.domain.model.AiConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Anthropic's Messages API, as pure functions.
 *
 * [GeminiProtocol]'s shape and [GeminiProtocol]'s reasons — the whole interest is in
 * what a response *means*, and a refused key, an exhausted credit balance, a reply
 * cut off at the bound and a candidate with no text in it are none of them producible
 * on demand from a live server.
 *
 * The wire format is simpler than Gemini's in one way that matters: the standing
 * instruction is a **top-level string** rather than a nested parts array, so
 * combining the connection's system prompt with the node's needs no special case
 * here at all — [RoutingAi] joins them and this sends the result.
 */
@Suppress("TooManyFunctions") // One member per wire concern; the API's own envelope sets the count.
internal object AnthropicProtocol : AiProtocol {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The published id [model] currently maps to.
     *
     * Three **stable aliases** and no dated snapshots — `claude-sonnet-5`, not
     * `claude-sonnet-5-20260101`. That is [GeminiProtocol.modelId]'s rule applied
     * before it had to be learned twice again: a dated id is one that gets retired
     * on somebody else's schedule, and an alias is what the provider re-points for
     * you. No `-preview` and no id restricted to a paid tier, for the same reason
     * the Gemini table names no pro model.
     */
    fun modelId(model: AiModel): String = when (model) {
        AiModel.FAST -> "claude-haiku-4-5"
        AiModel.BALANCED -> "claude-sonnet-5"
        AiModel.THOROUGH -> "claude-opus-5"
    }

    /**
     * Whether [model] is told to think, and how the field is spelled.
     *
     * **The per-tier branch is not tidiness — the wrong shape here is a 400 or an
     * empty answer.** Two separate facts collide:
     *
     * - `claude-haiku-4-5` predates adaptive thinking and **rejects**
     *   `{"type":"adaptive"}`. Omitting the field is the only correct thing to send,
     *   and it is also what makes the fast tier actually fast.
     * - On the other two, thinking tokens come out of the **same `max_tokens`
     *   budget as the reply** — the identical trap [GeminiProtocol]'s
     *   `thinkingHeadroom` exists for. A node asked for 200 tokens can spend all
     *   200 thinking and return no text at all.
     *
     * So the two thinking tiers get the same fixed headroom added on top of the
     * user's limit, generous on purpose: `max_tokens` is a cap and not a target, so
     * headroom nobody uses costs nothing where headroom that was needed and missing
     * costs the whole answer.
     */
    private fun thinks(model: AiModel): Boolean = model != AiModel.FAST

    private fun thinkingHeadroom(model: AiModel): Int = when (model) {
        AiModel.FAST -> 0
        AiModel.BALANCED -> LOW_THINKING_HEADROOM
        AiModel.THOROUGH -> HIGH_THINKING_HEADROOM
    }

    override fun endpoint(connection: AiConnection, model: AiModel): String =
        "${baseUrlFor(connection, BASE_URL)}/messages"

    override fun modelsEndpoint(connection: AiConnection): String =
        "${baseUrlFor(connection, BASE_URL)}/models"

    /**
     * `x-api-key` rather than a bearer token, and a **pinned API version**.
     *
     * `anthropic-version` is not optional and not a nicety: the server uses it to
     * decide which response shape to send, so a request without it is refused
     * outright. Pinning a date is what stops a future default from changing the
     * envelope this file parses, which is the same protection
     * [GeminiProtocol]'s tolerant reader buys by other means.
     */
    override fun headers(key: String): Map<String, String> = mapOf(
        CONTENT_TYPE_HEADER to JSON_CONTENT_TYPE,
        "x-api-key" to key,
        "anthropic-version" to API_VERSION,
    )

    /**
     * The request body for [request].
     *
     * `system` is omitted entirely when blank rather than sent empty, as Gemini's
     * is — an absent field and an empty string are not the same request, and only
     * one of them is what "no standing instruction" means.
     */
    override fun requestBody(request: AiRequest, connection: AiConnection): String = buildJsonObject {
        put(MODEL_KEY, modelIdFor(connection, request.model, modelId(request.model)))
        // The user's limit plus the thinking headroom, for the reason spelled out
        // on `thinks`: the two are drawn from one budget on the wire.
        put(
            MAX_TOKENS_KEY,
            request.maxOutputTokens.coerceAtLeast(1) + thinkingHeadroom(request.model),
        )
        if (request.systemInstruction.isNotBlank()) {
            put(SYSTEM_KEY, request.systemInstruction)
        }
        if (thinks(request.model)) {
            putJsonObject(THINKING_KEY) { put(TYPE_KEY, ADAPTIVE) }
        }
        putJsonArray(MESSAGES_KEY) {
            add(
                buildJsonObject {
                    put(ROLE_KEY, USER_ROLE)
                    put(CONTENT_KEY, request.prompt)
                },
            )
        }
    }.toString()

    /**
     * What [body] means, given the [status] it arrived with.
     *
     * `error.message` is read **before** the status, for [GeminiProtocol]'s reason:
     * "Your credit balance is too low to access the Anthropic API" is the sentence
     * somebody can act on, where a 400 is generic.
     *
     * The content array is filtered to `type == "text"` rather than indexed,
     * because a thinking tier answers with a thinking block first and reading
     * `content[0]` there would hand the user an empty string on every request that
     * worked.
     */
    @Suppress("ReturnCount") // Each exit names a distinct outcome; folding them is what loses the diagnosis.
    override fun readReply(status: Int, body: String): AiReply {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        if (status !in SUCCESS_RANGE) return AiReply(error = errorText(status, root))
        root ?: return AiReply(error = "The model returned something unreadable (HTTP $status)")
        val blocks = root[CONTENT_KEY]?.arrayOrNull()
            ?: return AiReply(error = "The model returned no answer")
        val text = blocks
            .mapNotNull { it.objectOrNull() }
            .filter { it[TYPE_KEY]?.stringOrNull() == TEXT_TYPE }
            .mapNotNull { it[TEXT_KEY]?.stringOrNull() }
            .joinToString(separator = "")
        val stop = root[STOP_KEY]?.stringOrNull().orEmpty()
        // An empty answer is a failure rather than an empty success, exactly as it
        // is for Gemini: a node pulsing on with "" looks like a prompt answered
        // with silence, and everything downstream acts on nothing.
        if (text.isBlank()) return AiReply(error = emptyAnswerText(stop))
        return AiReply(text = text, truncated = stop == MAX_TOKENS_STOP)
    }

    /** The listing, as `data[].id`. */
    override fun readModels(status: Int, body: String): AiModels {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        if (status !in SUCCESS_RANGE) return AiModels(error = errorText(status, root))
        val ids = root?.get(DATA_KEY)?.arrayOrNull().orEmpty()
            .mapNotNull { it.objectOrNull()?.get(ID_KEY)?.stringOrNull() }
        return AiModels(ids = ids)
    }

    /** A reply that came back with no text in it, named by why it stopped. */
    private fun emptyAnswerText(stop: String): String = when (stop) {
        MAX_TOKENS_STOP -> "The reply was cut off before any text was produced — raise the reply limit"
        REFUSAL_STOP -> "The model declined to answer this prompt"
        "", END_TURN_STOP -> "The model returned an empty answer"
        else -> "The model stopped without answering ($stop)"
    }

    /** `error.message` if the body carries one, else the bare status. */
    private fun errorText(status: Int, root: JsonObject?): String =
        errorMessage(root)?.let { "$it (HTTP $status)" } ?: httpOnlyText(status)

    private fun httpOnlyText(status: Int): String = when (status) {
        NO_RESPONSE -> "Could not reach the model — check the phone's connection"
        else -> "The model refused the request (HTTP $status)"
    }

    private const val BASE_URL = "https://api.anthropic.com/v1"

    /** Pinned so a future server default cannot change the envelope parsed above. */
    private const val API_VERSION = "2023-06-01"

    /** Enough for a model to check its own reasoning once, not to deliberate. */
    private const val LOW_THINKING_HEADROOM = 4_096

    /** Room for the tier whose whole point is that it takes its time. */
    private const val HIGH_THINKING_HEADROOM = 16_384

    private const val ADAPTIVE = "adaptive"
    private const val TEXT_TYPE = "text"
    private const val MAX_TOKENS_STOP = "max_tokens"
    private const val END_TURN_STOP = "end_turn"
    private const val REFUSAL_STOP = "refusal"
    private const val USER_ROLE = "user"
    private const val MODEL_KEY = "model"
    private const val MAX_TOKENS_KEY = "max_tokens"
    private const val SYSTEM_KEY = "system"
    private const val THINKING_KEY = "thinking"
    private const val MESSAGES_KEY = "messages"
    private const val ROLE_KEY = "role"
    private const val CONTENT_KEY = "content"
    private const val TYPE_KEY = "type"
    private const val TEXT_KEY = "text"
    private const val STOP_KEY = "stop_reason"
    private const val DATA_KEY = "data"
    private const val ID_KEY = "id"
}
