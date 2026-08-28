package com.example.ottomatic.data.ai

import com.example.ottomatic.core.service.AiModel
import com.example.ottomatic.core.service.AiReply
import com.example.ottomatic.core.service.AiRequest
import com.example.ottomatic.core.service.AiTool
import com.example.ottomatic.core.service.AiToolCall
import com.example.ottomatic.domain.model.AiConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The Gemini `generateContent` wire format, as pure functions.
 *
 * Holding no platform types at all, for
 * [com.example.ottomatic.domain.model.WebUrl]'s and
 * [com.example.ottomatic.domain.model.MessengerLink]'s reason: the interesting half
 * of this integration is *what the response means*, and that deserves JVM tests
 * where the transport half would need a device and a live key. Every failure shape
 * below — a refused key, an exhausted quota, a blocked prompt, an answer cut off at
 * the token bound, an empty candidate list — is a real response this has to read
 * correctly, and none of them can be produced on demand from a real server.
 *
 * **Model ids live here and nowhere above.** That is the whole point of [AiModel]
 * naming a trade-off rather than a product: when Google retires the id a workflow was
 * saved against, the fix is [modelId] and every persisted macro keeps working — or,
 * since the library grew per-connection overrides, a text field the user can fix
 * without waiting for an update at all.
 */
@Suppress("TooManyFunctions") // One member per wire concern; the API's own envelope sets the count.
internal object GeminiProtocol : AiProtocol {

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

    override fun endpoint(target: AiTarget): String {
        val id = modelIdFor(target, modelId(target.effort))
        return "${modelsEndpoint(target.connection)}/$id:$GENERATE_CONTENT"
    }

    override fun modelsEndpoint(connection: AiConnection): String =
        "${baseUrlFor(connection, BASE_URL)}/models"

    override fun headers(key: String): Map<String, String> = mapOf(
        CONTENT_TYPE_HEADER to JSON_CONTENT_TYPE,
        "x-goog-api-key" to key,
    )

    /**
     * The request body for [request].
     *
     * `systemInstruction` is omitted entirely rather than sent blank: the API
     * rejects a `parts` array containing an empty string, so "no standing
     * instruction" has to be an absent field and not an empty one.
     */
    override fun requestBody(request: AiRequest, target: AiTarget): String = buildJsonObject {
        if (request.systemInstruction.isNotBlank()) {
            putJsonObject(SYSTEM_KEY) {
                putJsonArray(PARTS_KEY) { add(buildJsonObject { put(TEXT_KEY, request.systemInstruction) }) }
            }
        }
        putJsonArray(CONTENTS_KEY) {
            add(
                buildJsonObject {
                    put(ROLE_KEY, USER_ROLE)
                    putJsonArray(PARTS_KEY) {
                        // Pictures before the words: a turn is read in order, and
                        // "what is in this photo?" ahead of the photo asks about
                        // nothing.
                        request.images.forEach { image ->
                            add(
                                buildJsonObject {
                                    putJsonObject(INLINE_DATA_KEY) {
                                        put(MIME_TYPE_KEY, image.mediaType)
                                        put(IMAGE_DATA_KEY, image.base64)
                                    }
                                },
                            )
                        }
                        // Sound rides the same inline part as a picture — one of the
                        // few places this API is simpler than the others, where audio
                        // needs its own content type entirely.
                        request.audio.forEach { clip ->
                            add(
                                buildJsonObject {
                                    putJsonObject(INLINE_DATA_KEY) {
                                        put(MIME_TYPE_KEY, clip.mediaType)
                                        put(IMAGE_DATA_KEY, clip.base64)
                                    }
                                },
                            )
                        }
                        // The blank prompt is filled in *here* rather than in the node,
                        // and that is what keeps the wire choice honest: `audioWireFor`
                        // reads the blank to mean "just transcribe it", so substituting
                        // earlier would send every transcription down the chat wire.
                        // This provider has no transcription endpoint, so a blank prompt
                        // always lands here and always needs the instruction spelt out.
                        add(buildJsonObject { put(TEXT_KEY, chatPrompt(request)) })
                    }
                },
            )
        }
        putJsonObject(GENERATION_KEY) {
            // The user's limit plus the thinking headroom, not either alone: the
            // two are drawn from one budget on the wire, and sending the bare
            // limit is what makes a thinking model answer nothing at all.
            put(MAX_TOKENS_KEY, request.maxOutputTokens.coerceAtLeast(1) + thinkingHeadroom(target.effort))
            // `thinkingLevel` alone, never beside `thinkingBudget`: sending both is
            // a 400 rather than a preference the server picks between.
            putJsonObject(THINKING_KEY) { put(THINKING_LEVEL_KEY, thinkingLevel(target.effort)) }
        }
    }.toString()

    /**
     * Which sounds this API will take inline, checked before the network.
     *
     * **`audio/mp4` is the one that matters, and it is the one this app produces.**
     * `action.record_audio` writes MPEG-4/AAC, so the most obvious macro anybody will
     * build — record a note, then transcribe it — is exactly the one that fails here.
     * Gemini's inline set is wav, mp3, aiff, aac, ogg and flac; an `.m4a` comes back as
     * a 400 that names neither the file nor the format, so the refusal is worded here
     * and names the fix instead.
     */
    override fun audioProblem(request: AiRequest, target: AiTarget): String? {
        val refused = request.audio.map { it.mediaType.lowercase() }.firstOrNull { it !in INLINE_AUDIO_TYPES }
            ?: return null
        return "Gemini cannot read $refused — it takes WAV, MP3, AIFF, AAC, OGG or FLAC. " +
            "\"Listen with AI\" records WAV, or convert the file first"
    }

    /**
     * The request body for an exchange that may use [tools].
     *
     * Two things are peculiar to this provider and both are load-bearing:
     *
     * - **A tool result is addressed by name, not by id.** Gemini mints no call id at
     *   all, so [AiToolCall.id] is synthesized in [readTurn] and never sent — the
     *   `functionResponse` names the function instead. That is also why a model
     *   asking for the same tool twice in one turn is answered in order.
     * - **`response` must be an object**, not the bare string every other provider
     *   accepts, so a result is wrapped in one key. A failed tool uses a different
     *   key so the model can see it failed without the wording having to say so.
     */
    override fun conversationBody(
        exchange: List<AiExchange>,
        tools: List<AiTool>,
        request: AiRequest,
        target: AiTarget,
    ): String = buildJsonObject {
        if (request.systemInstruction.isNotBlank()) {
            putJsonObject(SYSTEM_KEY) {
                putJsonArray(PARTS_KEY) { add(buildJsonObject { put(TEXT_KEY, request.systemInstruction) }) }
            }
        }
        if (tools.isNotEmpty()) {
            putJsonArray(TOOLS_KEY) {
                add(
                    buildJsonObject {
                        putJsonArray(DECLARATIONS_KEY) { tools.forEach { add(declare(it)) } }
                    },
                )
            }
        }
        putJsonArray(CONTENTS_KEY) { exchange.forEach { appendTurn(it) } }
        putJsonObject(GENERATION_KEY) {
            put(MAX_TOKENS_KEY, request.maxOutputTokens.coerceAtLeast(1) + thinkingHeadroom(target.effort))
            putJsonObject(THINKING_KEY) { put(THINKING_LEVEL_KEY, thinkingLevel(target.effort)) }
        }
    }.toString()

    /** One tool, in this API's spelling. */
    private fun declare(tool: AiTool): JsonObject = buildJsonObject {
        put(NAME_KEY, tool.name)
        put(DESCRIPTION_KEY, tool.description)
        put(PARAMETERS_KEY, toolParameterSchema(tool.parameters))
    }

    /** One thing that happened, as the `contents` entry this API expects for it. */
    private fun JsonArrayBuilder.appendTurn(entry: AiExchange) {
        when (entry) {
            is AiExchange.Ask -> add(
                buildJsonObject {
                    put(ROLE_KEY, USER_ROLE)
                    putJsonArray(PARTS_KEY) { add(buildJsonObject { put(TEXT_KEY, entry.prompt) }) }
                },
            )
            is AiExchange.Said -> entry.turn.raw?.let { raw ->
                add(buildJsonObject { put(ROLE_KEY, MODEL_ROLE); put(PARTS_KEY, raw) })
            }
            is AiExchange.Ran -> add(
                buildJsonObject {
                    put(ROLE_KEY, USER_ROLE)
                    putJsonArray(PARTS_KEY) {
                        entry.results.forEach { (call, result) ->
                            add(
                                buildJsonObject {
                                    putJsonObject(FUNCTION_RESPONSE_KEY) {
                                        put(NAME_KEY, call.name)
                                        putJsonObject(RESPONSE_KEY) {
                                            put(if (result.isError) FAILED_KEY else RESULT_KEY, result.text)
                                        }
                                    }
                                },
                            )
                        }
                    }
                },
            )
        }
    }

    /**
     * What [body] means when tools were offered.
     *
     * A turn's text and its function calls arrive as *sibling parts* of one content
     * object, so both are read from the same array — a model may narrate and then
     * call in the same breath, and dropping either half loses something.
     */
    @Suppress("ReturnCount") // Each exit names a distinct outcome, as in `readReply`.
    override fun readTurn(status: Int, body: String): AiTurn {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        if (status !in SUCCESS_RANGE) return AiTurn.failed(errorText(status, root))
        root ?: return AiTurn.failed("The model returned something unreadable (HTTP $status)")
        val candidate = root[CANDIDATES_KEY]?.arrayOrNull()?.firstOrNull()?.objectOrNull()
            ?: return AiTurn.failed(blockedText(root))
        val parts = candidate[CONTENT_KEY]?.objectOrNull()?.get(PARTS_KEY)?.arrayOrNull()
            ?: return AiTurn.failed("The model returned no answer")
        val text = parts.mapNotNull { it.objectOrNull()?.get(TEXT_KEY)?.stringOrNull() }
            .joinToString(separator = "")
        val calls = toolCallsIn(parts)
        val finish = candidate[FINISH_KEY]?.stringOrNull().orEmpty()
        if (calls.isEmpty() && text.isBlank()) return AiTurn.failed(emptyAnswerText(finish))
        return AiTurn(
            text = text,
            toolCalls = calls,
            raw = parts,
            truncated = finish == MAX_TOKENS_FINISH,
        )
    }

    /**
     * The turn's function calls, with ids synthesized.
     *
     * The id is never sent back — this API matches a response to its call by name —
     * but [AiToolCall] carries one so the engine has a single shape to log and
     * correlate against, and the index keeps two calls to the same tool distinct.
     */
    private fun toolCallsIn(parts: JsonArray): List<AiToolCall> = parts
        .mapNotNull { it.objectOrNull()?.get(FUNCTION_CALL_KEY)?.objectOrNull() }
        .mapIndexedNotNull { index, call ->
            val name = call[NAME_KEY]?.stringOrNull() ?: return@mapIndexedNotNull null
            AiToolCall(
                id = "$name-$index",
                name = name,
                arguments = call[ARGS_KEY]?.objectOrNull()?.asToolArguments().orEmpty(),
            )
        }

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
    override fun readReply(status: Int, body: String): AiReply {
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
     * The listing, with `models/` stripped off each name.
     *
     * Gemini answers with the *resource* name — `models/gemini-3.5-flash` — where
     * every other use of the id, including the URL a prompt is sent to, wants the
     * bare one. Entries that cannot answer a prompt at all (embedding models) are
     * dropped rather than offered, since choosing one produces a 400 that says
     * nothing about the field it came from.
     */
    override fun readModels(status: Int, body: String): AiModels {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        if (status !in SUCCESS_RANGE) return AiModels(error = errorText(status, root))
        val entries = root?.get(MODELS_KEY)?.arrayOrNull().orEmpty()
        val ids = entries.mapNotNull { entry ->
            val model = entry.objectOrNull() ?: return@mapNotNull null
            val supported = model[METHODS_KEY]?.arrayOrNull()
                ?.mapNotNull { it.stringOrNull() }
                .orEmpty()
            if (supported.isNotEmpty() && GENERATE_CONTENT !in supported) return@mapNotNull null
            val id = model[NAME_KEY]?.stringOrNull()?.removePrefix(MODEL_NAME_PREFIX)
                ?: return@mapNotNull null
            AiModelInfo(
                id = id,
                label = model[DISPLAY_NAME_KEY]?.stringOrNull().orEmpty(),
                // Gemini publishes generation *methods* and no modality field at all, so
                // there is nothing to answer with. Null rather than a guessed set: a
                // chooser reading "unknown" as "text only" would hide every model on this
                // provider from an audio filter, which is worse than not filtering.
                modalities = null,
            )
        }
        return AiModels(models = ids)
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
        val message = errorMessage(root)
        val code = error?.get(CODE_KEY)?.let { (it as? JsonPrimitive)?.intOrNull } ?: status
        return if (message == null) httpOnlyText(status) else "$message (HTTP $code)"
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

    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    private const val GENERATE_CONTENT = "generateContent"
    private const val MODEL_NAME_PREFIX = "models/"
    private const val MAX_TOKENS_FINISH = "MAX_TOKENS"
    private const val STOP_FINISH = "STOP"
    private const val USER_ROLE = "user"
    private const val MODEL_ROLE = "model"
    private const val TOOLS_KEY = "tools"
    private const val DECLARATIONS_KEY = "functionDeclarations"
    private const val DESCRIPTION_KEY = "description"
    private const val PARAMETERS_KEY = "parameters"
    private const val FUNCTION_CALL_KEY = "functionCall"
    private const val FUNCTION_RESPONSE_KEY = "functionResponse"
    private const val RESPONSE_KEY = "response"
    private const val ARGS_KEY = "args"
    /** What `inlineData` will carry as sound. Not `audio/mp4`, which is the trap. */
    private val INLINE_AUDIO_TYPES = setOf(
        "audio/wav",
        "audio/x-wav",
        "audio/mpeg",
        "audio/mp3",
        "audio/aiff",
        "audio/aac",
        "audio/ogg",
        "audio/flac",
    )

    private const val DISPLAY_NAME_KEY = "displayName"
    private const val INLINE_DATA_KEY = "inlineData"
    private const val MIME_TYPE_KEY = "mimeType"
    private const val IMAGE_DATA_KEY = "data"
    private const val RESULT_KEY = "result"

    /** Named apart from the file-level `ERROR_KEY`, which is the envelope's own. */
    private const val FAILED_KEY = "error"
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
    private const val CODE_KEY = "code"
    private const val MODELS_KEY = "models"
    private const val METHODS_KEY = "supportedGenerationMethods"
    private const val NAME_KEY = "name"
}
