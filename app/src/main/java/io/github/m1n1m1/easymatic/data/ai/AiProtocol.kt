package io.github.m1n1m1.easymatic.data.ai

import io.github.m1n1m1.easymatic.core.service.AiModel
import io.github.m1n1m1.easymatic.core.service.AiReply
import io.github.m1n1m1.easymatic.core.service.AiRequest
import io.github.m1n1m1.easymatic.core.service.AiTool
import io.github.m1n1m1.easymatic.domain.model.AiBaseUrl
import io.github.m1n1m1.easymatic.domain.model.AiConnection
import io.github.m1n1m1.easymatic.domain.model.AiModality
import io.github.m1n1m1.easymatic.domain.model.AiModelProfile
import io.github.m1n1m1.easymatic.domain.model.AiProvider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * One provider's wire format, as pure functions.
 *
 * **The interface exists because the transport does not vary and the format does.**
 * Every provider here is a JSON POST over HTTPS with a key in a header and a
 * ninety-second read timeout; what differs is the URL, the header name, the shape of
 * the body and — the interesting half — what a response *means*. So [AiTransport] is
 * written once and these are written per provider, which is the opposite of the
 * usual mistake of abstracting the part that is genuinely the same in each.
 *
 * Holding **no platform types at all** is the other half, and it is what
 * [GeminiProtocol] was already split out for: a refused key, an exhausted quota, a
 * blocked prompt, an answer cut off at the token bound and an empty candidate list
 * are all real responses this has to read correctly, and not one of them can be
 * produced on demand from a live server. They are JVM tests or they are untested.
 *
 * **Model ids live in the implementations and nowhere above**, which is the whole
 * point of [AiModel] naming a trade-off rather than a product — see [modelIdFor].
 */
@Suppress("TooManyFunctions") // Two wires, and the second needs four members of its own:
// where it is, what travels with the upload, how its answer reads, and whether this
// provider will take the sound at all. Splitting them into a second interface would put
// "can this protocol hear?" somewhere `protocolFor` does not reach.
internal interface AiProtocol {

    /** Where a prompt is sent for [target]. */
    fun endpoint(target: AiTarget): String

    /** Where this provider lists the models a key may use, for the editor's chooser. */
    fun modelsEndpoint(connection: AiConnection): String

    /** The auth and content headers for [key]. */
    fun headers(key: String): Map<String, String>

    /**
     * The request body for [request] sent at [target].
     *
     * [target] rather than [request] alone because the published model id lives in the
     * profile and the address lives on the account, and because a provider's tier
     * mapping is the thing most likely to churn under both.
     */
    fun requestBody(request: AiRequest, target: AiTarget): String

    /** What [body] means, given the [status] it arrived with. */
    fun readReply(status: Int, body: String): AiReply

    /**
     * The request body for an exchange that may use [tools], carrying everything said
     * so far in [exchange].
     *
     * **Parallel to [requestBody] rather than replacing it**, and that is worth a
     * sentence because collapsing the two looks tempting. Every existing test in this
     * package pins [requestBody]'s exact envelope — a blank system instruction
     * omitted, `thinkingLevel` sent without `thinkingBudget`, `max_completion_tokens`
     * on OpenAI and `max_tokens` everywhere else — and those pins are the whole value
     * of this file being pure functions. A tool-carrying body is a *different* shape
     * (Anthropic's `content` stops being a bare string; OpenAI grows a `tool` role
     * Gemini does not have), so it gets its own member and its own tests, and the
     * single-prompt path keeps working exactly as it did.
     *
     * Defaults to the single-prompt body, ignoring [tools]. That is the graceful
     * degradation a protocol which has not implemented tools should have: its node
     * answers directly instead of failing, and nothing needs a capability flag to say
     * so.
     */
    fun conversationBody(
        exchange: List<AiExchange>,
        tools: List<AiTool>,
        request: AiRequest,
        target: AiTarget,
    ): String = requestBody(request, target)

    /**
     * What [body] means when tools were offered.
     *
     * Separate from [readReply] for the reason [AiTurn] exists at all: a turn that
     * asks for a tool has to carry the calls *and* the assistant's own turn verbatim,
     * neither of which fits an [AiReply]. It also must not treat blank text as a
     * failure the way [readReply] correctly does — a tool-calling turn has null or
     * absent text on OpenAI and Gemini, and would otherwise be reported as "the model
     * returned an empty answer" on every request that worked.
     */
    fun readTurn(status: Int, body: String): AiTurn {
        val reply = readReply(status, body)
        return AiTurn(text = reply.text, error = reply.error, truncated = reply.truncated)
    }

    /** What a listing response means. Never throws; a failure is a sentence. */
    fun readModels(status: Int, body: String): AiModels

    /**
     * What is missing before [target] can be used, or null when it is ready. Checked
     * before anything reaches the network.
     *
     * Null for the providers that publish their own endpoint and model table, which
     * is why it defaults rather than being declared on each: there is nothing a user
     * can leave out of a Gemini connection but the key, and the key has its own
     * message. It exists for the open-ended providers, where a blank field would
     * otherwise surface as a `MalformedURLException` swallowed into [NO_RESPONSE]
     * and worded "check the phone's connection" — sending somebody to look at their
     * Wi-Fi over an empty box in this app.
     */
    fun configurationProblem(target: AiTarget): String? = null

    /**
     * Why this provider will not take the sound on [request], or null when it will.
     * Checked before anything reaches the network.
     *
     * **The one capability question this file has ever had to answer, and pictures
     * never needed it.** Every provider here that sees images at all accepts the same
     * four image types, so an image's media type never decided whether a request was
     * sendable — `action.ai_describe` can hand any picture to any model and let a
     * self-hosted server that cannot see say so in its own words. Sound is not like
     * that in either direction: Claude has no audio content block at all, Gemini's
     * inline set excludes `audio/mp4` — which is what this app's own recorder writes —
     * and OpenAI's chat wire takes wav and mp3 and nothing else. Those are facts about
     * the *protocol*, fixed and knowable here, so leaving them to the server means a
     * generic 400 naming neither the file nor the reason. The skill's rule for a
     * picture whose kind is unknown, applied one step further out.
     *
     * Takes the [request] rather than only the [target] because the answer depends on
     * the media type carried, which is not a property of the account or the profile.
     *
     * Null by default: a protocol that says nothing is one for which sound is not a
     * special case, which is the honest default for a member the audio nodes are the
     * only callers of.
     */
    fun audioProblem(request: AiRequest, target: AiTarget): String? = null

    /**
     * Where this provider transcribes an audio file, or null when it has no such
     * endpoint.
     *
     * **The second wire, and the only reason there is one.** A chat request carrying
     * sound needs a model that can hear, which on a self-hosted server almost never
     * exists — what such a server runs is Whisper, behind `POST /audio/transcriptions`,
     * which is also what `whisper.cpp`, `faster-whisper` and LM Studio all serve. That
     * endpoint is the fully offline path, and audio is the payload where offline
     * matters most. Null here means "this provider has one wire", which is the answer
     * for Gemini (it publishes no transcription endpoint on the developer API) and for
     * OpenRouter (its endpoint takes a JSON body of its own rather than OpenAI's
     * multipart, so pretending otherwise would be a 404 dressed as a network failure).
     *
     * Which wire a request actually takes is [audioWireFor]'s decision, not this one's.
     */
    fun transcriptionEndpoint(target: AiTarget): String? = null

    /**
     * The form fields sent beside the uploaded clip — the model id, at least.
     *
     * On the protocol rather than in `RoutingAi` because resolving a published model id
     * needs this provider's own table, which is exactly what [requestBody] takes an
     * [AiTarget] for.
     */
    fun transcriptionFields(target: AiTarget): Map<String, String> = emptyMap()

    /**
     * What a transcription response means.
     *
     * **Deliberately not defaulted to [readReply].** A transcription body is
     * `{"text": …}`, which every one of these readers would parse as a response with no
     * candidates in it and report as "the model returned an empty answer" — a sentence
     * about the model that is really a sentence about the code. Defaulting to a refusal
     * makes the omission say what it is. It is unreachable while
     * [transcriptionEndpoint] answers null, which is the point.
     */
    fun readTranscription(status: Int, body: String): AiReply =
        AiReply(error = "This provider has no transcription endpoint")
}

/**
 * The account and the saved way of asking, which always travel together.
 *
 * One parameter rather than two everywhere below, because every question a protocol
 * asks needs both halves and neither is meaningful alone: the address, the key and
 * the provider come from the connection, and the published model id and the effort
 * come from the profile. Passing them separately is how a body eventually gets built
 * for one profile and sent to another account's endpoint.
 */
internal data class AiTarget(
    val connection: AiConnection,
    val profile: AiModelProfile,
) {
    /** The trade-off this profile asks for, which is what the tier tables key on. */
    val effort: AiModel get() = profile.effort
}

/**
 * What a provider answered when asked which models a key may use.
 *
 * [AiReply]'s shape for [AiReply]'s reason: nothing here throws, a non-blank [error]
 * always comes with an empty [ids], and the caller has one thing to do with every
 * way it can fail. A provider that serves no listing at all is one of those ways —
 * the editor reports it and leaves the field typable, because a server without
 * `/models` is still perfectly usable.
 */
data class AiModels(
    val models: List<AiModelInfo> = emptyList(),
    val error: String = "",
) {
    /**
     * Just the ids, for the callers that only ever wanted those.
     *
     * Derived rather than stored so the two can never disagree, and kept so that adding
     * capabilities changed the shape only where somebody actually wanted them.
     */
    val ids: List<String> get() = models.map { it.id }
}

/**
 * One model a key can reach, with whatever the provider was willing to say about it.
 *
 * **`null` [modalities] means "this provider does not publish it", never "this model
 * takes nothing"**, and that distinction is the whole contract. Only OpenRouter states
 * what each model accepts; Gemini publishes generation methods and no modality field at
 * all, and OpenAI and Anthropic publish little more than ids. A chooser that read a
 * missing answer as "no" would hide every usable model on three providers out of five.
 *
 * This is `CapabilityStatus.UNKNOWN`'s rule and `PickerOptions`' degradation rule, in a
 * third place: an empty answer means the question could not be asked, so nothing is
 * narrowed and the screen says why.
 *
 * [label] is the provider's own display name where it gives one — "Claude Opus 5" beside
 * `claude-opus-5` — and blank where it does not, in which case the id is the name.
 */
data class AiModelInfo(
    val id: String,
    val label: String = "",
    val modalities: Set<AiModality>? = null,
)

/**
 * Which wire a request carrying sound takes, or why it takes none.
 *
 * **One function, called once, so the two decisions cannot drift.** Choosing the wire
 * and deciding whether the media type is acceptable are the same question asked twice —
 * OpenAI's chat endpoint takes wav and mp3 where its transcription endpoint takes eight
 * formats, so "is this sendable?" has no answer until "sent where?" does. Split across
 * `RoutingAi` and a protocol they would eventually disagree, and the shape of that bug
 * is a file refused for being an `.m4a` on the one wire that would have accepted it.
 *
 * It is a file-level function on [combineInstructions]' and [replyLimit]'s precedent,
 * for their reason: pure, needing no repository, no key and no network, so the whole
 * decision table is a JVM test.
 *
 * **The rule is one sentence.** A request with sound and *no prompt* is somebody asking
 * for a transcript, which is what a transcription endpoint is; anything else is a
 * question about a recording, which only a chat model can answer. Nothing about the
 * mechanism is exposed — the user leaves a box empty or fills it in.
 */
@Suppress("ReturnCount") // No sound, a transcript wanted, and a question asked are three
// distinct routes; folding them is what loses the one rule this function exists to state.
internal fun audioWireFor(request: AiRequest, protocol: AiProtocol, target: AiTarget): AudioWire {
    if (request.audio.isEmpty()) return AudioWire.Chat
    val transcription = protocol.transcriptionEndpoint(target)
    if (request.isTranscription && transcription != null) return AudioWire.Transcription(transcription)
    return protocol.audioProblem(request, target)?.let { AudioWire.Refused(it) } ?: AudioWire.Chat
}

/**
 * Whether this request asks for a transcript rather than for an answer.
 *
 * **A blank question beside a clip is the setting, not a missing field** — it is what
 * `action.ai_transcribe` and the listen nodes mean by leaving *What to ask* empty, and it
 * is what [audioWireFor] reads to choose the transcription endpoint.
 *
 * It lives here rather than on [AiRequest] because it is a fact about how this package
 * routes a request, not about the request itself; `core/` knows nothing of wires.
 */
internal val AiRequest.isTranscription: Boolean
    get() = prompt.isBlank() && audio.isNotEmpty()

/**
 * What to say to a chat model that has been handed a clip and no question.
 *
 * **Shared by every chat renderer rather than written per protocol, because leaving it
 * out is silent.** It began on Gemini alone, which was the only provider whose blank
 * prompt reached a chat body at all — and then OpenRouter, which has audio-capable chat
 * models and no transcription endpoint, started sending a clip beside an **empty** text
 * part. A model given audio and nothing to do with it does whatever it likes: usually it
 * answers conversationally, sometimes it describes the recording, and nothing anywhere
 * reports that a transcript was not what came back.
 *
 * The wording does two jobs and both are load-bearing. It asks for the words, and it
 * forbids the framing — models reliably reach for "Sure! Here is the transcript:" and a
 * closing remark, which is not a transcript and is exactly what a macro then mails to
 * somebody. English, like every other string a model rather than a person reads.
 */
internal const val TRANSCRIBE_INSTRUCTION: String =
    "Transcribe the audio word for word. Output only the transcript itself: no preamble, " +
        "no explanation, no commentary, no quotation marks, and no closing remark. " +
        "If the audio contains no speech, output nothing at all."

/**
 * The text a chat turn carries for [request] — the question, or the instruction to
 * transcribe when there is none.
 *
 * One function so the three renderers cannot disagree, which they already had.
 */
internal fun chatPrompt(request: AiRequest): String =
    if (request.isTranscription) TRANSCRIBE_INSTRUCTION else request.prompt

/** Where a request carrying sound is going. */
internal sealed interface AudioWire {

    /** The ordinary chat body, with the sound rendered into it. */
    data object Chat : AudioWire

    /** A multipart upload to [url], answering a transcript and nothing else. */
    data class Transcription(val url: String) : AudioWire

    /** Not sendable at all, with the sentence saying why. */
    data class Refused(val error: String) : AudioWire
}

/**
 * The file half of a multipart request.
 *
 * A holder rather than four more parameters on [AiTransport.postMultipart], on
 * `RecordingRequest`'s stated reasoning — and because seven positional parameters is a
 * detekt failure as well as an unreadable call.
 */
internal data class AiUpload(
    val fieldName: String,
    val fileName: String,
    val mediaType: String,
    val bytes: ByteArray,
) {
    // `ByteArray` has identity equality, which a data class would otherwise inherit
    // silently into `==`. Nothing compares these, so the honest thing is to say so
    // rather than to generate a deep comparison nobody asked for.
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * The protocol for [provider], or **null** for one that has no wire.
 *
 * The one `when` over the enum on the execution path.
 *
 * **Null is an answer rather than a failure**, and it is what keeps this file honest. An
 * on-device provider has no endpoint, no headers, no key and no body, so there is nothing
 * for the six abstract members above to return; a stub object would have to invent five
 * URLs and a JSON envelope for a request that is an AIDL call. Both callers —
 * `RoutingAi.resolve` and `AiModelCatalog.list` — ask
 * [io.github.m1n1m1.easymatic.domain.model.isOnDevice] first and take a different path
 * entirely, so this is never dereferenced.
 */
internal fun protocolFor(provider: AiProvider): AiProtocol? = when (provider) {
    AiProvider.GEMINI -> GeminiProtocol
    AiProvider.ANTHROPIC -> AnthropicProtocol
    AiProvider.OPENAI -> OpenAiProtocol.OpenAi
    AiProvider.OPENROUTER -> OpenAiProtocol.OpenRouter
    AiProvider.OPENAI_COMPATIBLE -> OpenAiProtocol.SelfHosted
    AiProvider.ML_KIT -> null
}

/**
 * The published id this profile asks for: the one it names, else the provider's own
 * table for its effort.
 *
 * **The override is not a power-user knob, it is the fix for the failure this table
 * has already had twice.** `GeminiProtocol`'s ids were withdrawn once and quietly
 * restricted to existing keys once, and both times the app was broken on somebody
 * else's phone with nothing to do about it but wait for an update. Naming the model
 * on the profile makes that a text field instead. For a self-hosted server there is
 * no table to fall back to at all — the model is whatever that machine was started
 * with — so [default] is blank there and the field is required.
 *
 * It became one field rather than a three-armed `when` over the tier when the tiers
 * moved onto the profile: a profile *is* one tier, so there is nothing left to choose
 * between.
 */
internal fun modelIdFor(target: AiTarget, default: String): String =
    target.profile.modelId.trim().ifBlank { default }

/**
 * The connection's own base URL if it has one, else [default].
 *
 * Run through [AiBaseUrl] rather than used raw so that a pasted endpoint and a
 * trailing slash are tidied the same way here as in the editor — two readings of one
 * field are two readings that drift.
 */
internal fun baseUrlFor(connection: AiConnection, default: String): String =
    AiBaseUrl.parse(connection.baseUrl) ?: default

/**
 * `error.message` where a response carries one.
 *
 * Shared by all three protocols because all three nest it identically, and lifted
 * out for [GeminiProtocol.errorText]'s reason: that sentence — "API key not valid",
 * "You exceeded your current quota", "credit balance is too low" — is the one the
 * user can act on, where an HTTP status alone reads as generic failure.
 */
internal fun errorMessage(root: JsonObject?): String? =
    root?.get(ERROR_KEY)?.objectOrNull()?.get(MESSAGE_KEY)?.stringOrNull()?.takeIf { it.isNotBlank() }

/**
 * What the transport reports for a request that never happened, matching
 * [io.github.m1n1m1.easymatic.core.service.SystemServices.httpRequest]'s own `-1` so the
 * two failure paths read the same way.
 */
internal const val NO_RESPONSE = -1

internal val SUCCESS_RANGE = 200..299

internal const val ERROR_KEY = "error"

internal const val MESSAGE_KEY = "message"

internal const val CONTENT_TYPE_HEADER = "Content-Type"

internal const val JSON_CONTENT_TYPE = "application/json"

/**
 * Three readers that answer null rather than throwing on a field that is not the
 * shape it was last time.
 *
 * File-level and `internal` rather than members of one protocol, because all of them
 * need the same tolerance for the same reason: these envelopes gain fields with
 * every model release, and refusing to parse one that grew a field would turn a
 * working integration into a broken one on somebody else's release schedule. They
 * are also why nothing here needs an `@Serializable` mirror of every optional field
 * a provider has ever shipped.
 */
internal fun JsonElement.objectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()

internal fun JsonElement.arrayOrNull(): JsonArray? = runCatching { jsonArray }.getOrNull()

internal fun JsonElement.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * A model's tool arguments, as the text every consumer of them wants.
 *
 * **Shared by all three protocols because all three hand back the same thing under a
 * different key** — Gemini's `functionCall.args`, Anthropic's `tool_use.input` and
 * OpenAI's parsed `function.arguments` are one JSON object each.
 *
 * A string value yields its *content* rather than its quoted form, which is the whole
 * reason this is not `toString()`: a config key given `"kitchen"` must decode to
 * `kitchen`, and the quotes would survive into a Wi-Fi name or a notification title.
 * Anything else — a number, a boolean, a nested object a model sent where a scalar
 * was asked for — keeps its JSON text, which is exactly what `Item.asText()` does
 * with the same values and what `NodeSchema.decode` then parses back.
 */
internal fun JsonObject.asToolArguments(): Map<String, String> =
    mapValues { (_, value) -> value.stringOrNull() ?: value.toString() }
