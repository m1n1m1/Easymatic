package com.example.ottomatic.data.ai

import com.example.ottomatic.core.service.AiModel
import com.example.ottomatic.core.service.AiReply
import com.example.ottomatic.core.service.AiRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The Gemini `generateContent` wire format, as pure functions.
 *
 * Split out from [GeminiAi] and holding no platform types at all, for
 * [com.example.ottomatic.domain.model.WebUrl]'s and
 * [com.example.ottomatic.domain.model.MessengerLink]'s reason: the interesting
 * half of this integration is *what the response means*, and that deserves JVM
 * tests where the transport half would need a device and a live key. Every
 * failure shape below — a refused key, an exhausted quota, a blocked prompt, an
 * answer cut off at the token bound, an empty candidate list — is a real response
 * this has to read correctly, and none of them can be produced on demand from a
 * real server.
 *
 * **Model ids live here and nowhere above.** That is the whole point of
 * [AiModel] naming a trade-off rather than a product: when Google retires the id
 * a workflow was saved against, the fix is [modelId] and every persisted macro
 * keeps working.
 */
internal object GeminiProtocol {

    /**
     * A tolerant reader, deliberately. The response carries far more than is read
     * here — safety ratings, token counts, grounding metadata — and gains more
     * with every model. Refusing to parse an envelope that grew a field would
     * turn a working integration into a broken one on Google's release schedule.
     */
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The published id [model] currently maps to.
     *
     * The `-latest`-style aliases are avoided on purpose: an alias that silently
     * re-points is exactly the thing that makes a macro behave differently one
     * morning with nothing edited, which is much harder to diagnose than a model
     * that has been retired and says so.
     *
     * **`-preview` ids are avoided for the same reason, and that one is learned
     * rather than assumed.** This table first pointed at `gemini-3-pro-preview`,
     * which was shut down; and the 2.5 family it pointed at next stopped being
     * offered to *new* keys while continuing to work for old ones — a failure that
     * appears only on somebody else's phone. So all three tiers now name **stable
     * flash-family** ids: no preview to be withdrawn, and no pro tier that a free
     * key may not be entitled to.
     *
     * Three ids in capability order — lite, flash, latest flash — each with its own
     * [thinkingLevel]. That is the whole reason [AiModel] names a trade-off:
     * this list churns on Google's schedule, and every persisted macro survives it.
     */
    fun modelId(model: AiModel): String = when (model) {
        AiModel.FAST -> "gemini-3.5-flash-lite"
        AiModel.BALANCED -> "gemini-3.5-flash"
        AiModel.THOROUGH -> "gemini-3.6-flash"
    }

    /**
     * How hard [model] is told to think before answering.
     *
     * **Gemini 3 replaced `thinkingBudget` with `thinkingLevel`, and sending both
     * is a 400.** The old field is still accepted for backward compatibility, but
     * mixing them is an error and the qualitative one is what these models are
     * tuned for — so this sends `thinkingLevel` and nothing else.
     *
     * The other half of that change matters more: **thinking can no longer be
     * turned off.** `minimal` is documented as matching "no thinking" for most
     * queries and explicitly does *not* guarantee it. So the fast tier is now the
     * *least* thinking available rather than none, and every tier has to carry the
     * headroom that used to be needed by only two — see [thinkingHeadroom].
     */
    private fun thinkingLevel(model: AiModel): String = when (model) {
        AiModel.FAST -> "minimal"
        AiModel.BALANCED -> "low"
        AiModel.THOROUGH -> "high"
    }

    /**
     * Tokens added to the user's reply limit to cover [model]'s thinking.
     *
     * **This is the field that decides whether the node works at all**, and it is
     * the one genuine trap in this API. Thinking tokens are drawn from the *same*
     * `maxOutputTokens` budget as the reply — so a node asked for a 200-token
     * answer can spend all 200 thinking, stop at `MAX_TOKENS`, and return a
     * candidate with no text in it. The user sees a node that fails for no visible
     * reason and a limit that appears to do the opposite of what it says.
     *
     * A fixed allowance per level rather than a computed one, because
     * `thinkingLevel` is qualitative and there is no token figure to add. It is
     * generous on purpose: `maxOutputTokens` is a **cap and not a target**, so
     * headroom the model does not use costs exactly nothing, where headroom it
     * needed and did not get costs the whole answer.
     */
    private fun thinkingHeadroom(model: AiModel): Int = when (model) {
        AiModel.FAST -> MINIMAL_THINKING_HEADROOM
        AiModel.BALANCED -> LOW_THINKING_HEADROOM
        AiModel.THOROUGH -> HIGH_THINKING_HEADROOM
    }

    /** Where a [modelId] is asked. */
    fun endpoint(modelId: String): String = "$BASE_URL/$modelId:generateContent"

    /**
     * The request body for [request].
     *
     * `systemInstruction` is omitted entirely rather than sent blank: the API
     * rejects a `parts` array containing an empty string, so "no standing
     * instruction" has to be an absent field and not an empty one.
     */
    fun requestBody(request: AiRequest): String = buildJsonObject {
        if (request.systemInstruction.isNotBlank()) {
            putJsonObject(SYSTEM_KEY) {
                putJsonArray(PARTS_KEY) { add(buildJsonObject { put(TEXT_KEY, request.systemInstruction) }) }
            }
        }
        putJsonArray(CONTENTS_KEY) {
            add(
                buildJsonObject {
                    put(ROLE_KEY, USER_ROLE)
                    putJsonArray(PARTS_KEY) { add(buildJsonObject { put(TEXT_KEY, request.prompt) }) }
                },
            )
        }
        putJsonObject(GENERATION_KEY) {
            // The user's limit plus the thinking headroom, not either alone: the
            // two are drawn from one budget on the wire, and sending the bare
            // limit is what makes a thinking model answer nothing at all.
            put(MAX_TOKENS_KEY, request.maxOutputTokens.coerceAtLeast(1) + thinkingHeadroom(request.model))
            // `thinkingLevel` alone, never beside `thinkingBudget`: sending both is
            // a 400 rather than a preference the server picks between.
            putJsonObject(THINKING_KEY) { put(THINKING_LEVEL_KEY, thinkingLevel(request.model)) }
        }
    }.toString()

    /**
     * What [body] means, given the [status] it arrived with.
     *
     * A non-2xx status is read for `error.message` **before** anything else,
     * because that string is the one the user can act on — "API key not valid",
     * "quota exceeded for quota metric" — where the status code alone reads as a
     * generic failure. A body that is not JSON at all still reports the status,
     * rather than becoming a parse error that names nothing.
     */
    @Suppress("ReturnCount") // Each exit names a distinct outcome; folding them is what loses the diagnosis.
    fun readReply(status: Int, body: String): AiReply {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        if (status !in SUCCESS_RANGE) return AiReply(error = errorText(status, root))
        root ?: return AiReply(error = "The model returned something unreadable (HTTP $status)")
        val candidate = root[CANDIDATES_KEY]?.arrayOrNull()?.firstOrNull()?.objectOrNull()
            ?: return AiReply(error = blockedText(root))
        val text = candidate[CONTENT_KEY]?.objectOrNull()
            ?.get(PARTS_KEY)?.arrayOrNull()
            ?.mapNotNull { it.objectOrNull()?.get(TEXT_KEY)?.stringOrNull() }
            ?.joinToString(separator = "")
            .orEmpty()
        val finish = candidate[FINISH_KEY]?.stringOrNull().orEmpty()
        // An empty answer is a failure rather than an empty success: a node that
        // pulsed on with "" would look exactly like one whose prompt was answered
        // with silence, and downstream nodes would act on nothing.
        if (text.isBlank()) return AiReply(error = emptyAnswerText(finish))
        return AiReply(text = text, truncated = finish == MAX_TOKENS_FINISH)
    }

    /**
     * A refusal reported without any candidate at all — a prompt blocked before
     * the model saw it. `promptFeedback.blockReason` is the only thing that says
     * so, and without it this reads as an inexplicable empty success.
     */
    private fun blockedText(root: JsonObject): String {
        val reason = root[FEEDBACK_KEY]?.objectOrNull()?.get(BLOCK_KEY)?.stringOrNull()
        return if (reason.isNullOrBlank()) {
            "The model returned no answer"
        } else {
            "The prompt was blocked before it reached the model ($reason)"
        }
    }

    /** A candidate that came back with no text in it, named by why it stopped. */
    private fun emptyAnswerText(finish: String): String = when (finish) {
        MAX_TOKENS_FINISH -> "The reply was cut off before any text was produced — raise the reply limit"
        "", STOP_FINISH -> "The model returned an empty answer"
        else -> "The model stopped without answering ($finish)"
    }

    /** `error.message` if the body carries one, else the bare status. */
    private fun errorText(status: Int, root: JsonObject?): String {
        val error = root?.get(ERROR_KEY)?.objectOrNull()
        val message = error?.get(MESSAGE_KEY)?.stringOrNull()
        val code = error?.get(CODE_KEY)?.let { (it as? JsonPrimitive)?.intOrNull } ?: status
        return if (message.isNullOrBlank()) httpOnlyText(status) else "$message (HTTP $code)"
    }

    /**
     * The fallback wording when the server said nothing readable. [NO_RESPONSE] is
     * what the transport reports for a request that never left the phone, so it
     * must not be dressed up as an HTTP status the server never sent.
     */
    private fun httpOnlyText(status: Int): String = when (status) {
        NO_RESPONSE -> "Could not reach the model — check the phone's connection"
        else -> "The model refused the request (HTTP $status)"
    }

    /**
     * What the transport reports for a request that never happened, matching
     * [com.example.ottomatic.core.service.SystemServices.httpRequest]'s own `-1`
     * so the two failure paths read the same way.
     */
    const val NO_RESPONSE = -1

    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
    private val SUCCESS_RANGE = 200..299
    private const val MAX_TOKENS_FINISH = "MAX_TOKENS"
    private const val STOP_FINISH = "STOP"
    private const val USER_ROLE = "user"
    private const val SYSTEM_KEY = "systemInstruction"
    private const val CONTENTS_KEY = "contents"
    private const val GENERATION_KEY = "generationConfig"
    private const val MAX_TOKENS_KEY = "maxOutputTokens"
    private const val THINKING_KEY = "thinkingConfig"
    private const val THINKING_LEVEL_KEY = "thinkingLevel"

    /** "Minimal" is not "none", so even the fast tier needs room to think. */
    private const val MINIMAL_THINKING_HEADROOM = 1_024

    /** Enough for a model to check its own reasoning once, not to deliberate. */
    private const val LOW_THINKING_HEADROOM = 4_096

    /** Room for the tier whose whole point is that it takes its time. */
    private const val HIGH_THINKING_HEADROOM = 16_384
    private const val PARTS_KEY = "parts"
    private const val TEXT_KEY = "text"
    private const val ROLE_KEY = "role"
    private const val CANDIDATES_KEY = "candidates"
    private const val CONTENT_KEY = "content"
    private const val FINISH_KEY = "finishReason"
    private const val FEEDBACK_KEY = "promptFeedback"
    private const val BLOCK_KEY = "blockReason"
    private const val ERROR_KEY = "error"
    private const val MESSAGE_KEY = "message"
    private const val CODE_KEY = "code"
}

/**
 * Three readers that answer null rather than throwing on a field that is not the
 * shape it was last time.
 *
 * File-level rather than members of [GeminiProtocol] so the object stays about the
 * protocol; they are also the reason nothing here needs a typed response class.
 * A response envelope this permissive would otherwise need an `@Serializable`
 * mirror of every optional field Google has ever shipped.
 */
private fun JsonElement.objectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()

private fun JsonElement.arrayOrNull(): JsonArray? = runCatching { jsonArray }.getOrNull()

private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
