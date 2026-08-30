package io.github.m1n1m1.easymatic.core.service

import kotlinx.serialization.Serializable

/**
 * A language model, as the engine sees it.
 *
 * **Suspending**, owning its dispatcher in the `data/` half, for [Mail]'s and
 * [SmartHome]'s reason: the node that reaches this is not written by somebody
 * thinking about threads.
 *
 * **Nothing here throws.** [complete] answers an [AiReply] carrying an error
 * string, mirroring [SmartHome] and [SystemServices.httpRequest] answering `-1`
 * for a request that never happened: an unset key, a refused key, a quota that
 * ran out, no network and a model that returned nothing all reach the node as
 * one shape, and the node has one thing to do with all of them.
 *
 * **An action's facade and never a value node's**, and provably so for the reason
 * [mail][Mail]'s and [smartHome][SmartHome]'s are: every call is a network round
 * trip, which is both of the things the pull side may not be — slow and failable.
 * There is no `value.ai` and there will not be one; asking a model something is
 * `action.ai_prompt` on the exec wire, where the latency and the failure are
 * visible in the run log.
 *
 * **Generic rather than `Gemini`**, and the test of whether that abstraction is
 * real is the same one `data/hue/` passes: nothing vendor-shaped crosses this
 * boundary. [AiModel] names a *speed/quality trade-off* rather than a published
 * model id, an endpoint, a JSON envelope and an API key all stop at `data/ai/`,
 * and a second provider — or Gemini Nano running on-device — is a new
 * implementation of this interface with nothing edited here, in `engine/`, or in
 * the node.
 */
interface Ai {

    /** Answers [request], or reports in [AiReply.error] why it could not. */
    suspend fun complete(request: AiRequest): AiReply

    /**
     * Answers [request], letting the model call [tools] along the way, and stopping
     * after at most [maxTurns] replies from it.
     *
     * **Two methods rather than one with an empty list**, because the two make
     * different promises and a caller chooses between them knowingly: [complete] is
     * one round trip that either answers or does not, where this one may run
     * arbitrary [invoke] work an unbounded number of times up to the cap. A node
     * whose card says "Ask AI" and a node whose card says "Ask AI (with tools)"
     * should not be the same call with a different argument. An empty [tools] is
     * still legal and simply delegates — a tool list the user emptied is not an
     * error.
     *
     * **The loop lives below this interface, not above it.** `engine/` supplies what
     * a tool *is* and what running one *does*; the turn bookkeeping — which shape a
     * reply-with-tool-calls takes, which parts of the assistant's turn have to be
     * echoed back verbatim, how a result is addressed — is per provider and stops in
     * `data/ai/`, exactly as the request envelope does. That is also why [invoke] is
     * a callback rather than this returning tool calls for the caller to service: a
     * caller servicing them would have to reproduce the echo rules, and those are the
     * part that differs.
     *
     * [invoke] is called once per tool the model asks for. It must not throw — an
     * [AiToolResult] with `isError` set is how a tool reports that it failed, and the
     * model gets to try something else.
     */
    suspend fun converse(
        request: AiRequest,
        tools: List<AiTool>,
        maxTurns: Int = AiToolLimits.DEFAULT_MAX_TURNS,
        invoke: suspend (AiToolCall) -> AiToolResult,
    ): AiReply

    /**
     * What the model profile [modelRef] names is allowed to do, as the `ToolSpec` text
     * its editor wrote. Blank when nothing is, or when the profile is gone.
     *
     * **The seam that keeps the tool list out of `engine/`.** Which tools a model may
     * use is a property of the *connection library*, which lives in `data/`; building
     * a catalogue and running a call needs an `ExecutionContext`, which lives in
     * `engine/`. So the list crosses as text through this facade, exactly as every
     * other fact about a connection already does, and nothing library-shaped reaches
     * `core/` — a `ToolSpec` is parsed on the far side by the caller that will run it.
     */
    suspend fun toolsFor(modelRef: String): String
}

/**
 * Which model to ask, named by what the user is choosing between rather than by
 * what the provider currently calls it.
 *
 * **This is the load-bearing half of "generic rather than Gemini", and it is not
 * only tidiness.** Published model ids churn on a scale of months —
 * `gemini-2.0-flash` to `gemini-3.5-flash` to whatever is next — and anything
 * *persisted* that names one turns into a broken reference the day that id is
 * retired, with the user having chosen nothing wrong. A trade-off outlives the model
 * that currently implements it: the mapping lives in `data/ai/`, where changing it is
 * one line that nothing persisted notices.
 *
 * **It is no longer a node's config, and the argument is stronger for the move.**
 * This used to be a property of every AI node, so a workflow persisted the enum's
 * name — safe, because an enum name does not churn, but it also meant a macro chose
 * between three trade-offs and could say nothing else about the model. It is now a
 * field of an [io.github.m1n1m1.easymatic.domain.model.AiModelProfile], and what a
 * workflow persists is that profile's **id**: a string the user minted, which churns
 * not at all, and behind which the tier, the published id, the persona and the tool
 * permissions can all be re-decided without touching a macro.
 *
 * `@Serializable` because it is persisted in the connection library.
 *
 * Carries no `@Label`s, which is a package rule rather than an omission: that
 * annotation lives in `domain`, and `core` may not import it. It costs nothing —
 * `NodeSchema` prettifies an unlabelled enum name, and "Fast" / "Balanced" /
 * "Thorough" is what these already say.
 */
@Serializable
enum class AiModel {
    /**
     * Cheapest and quickest. The default, and right for almost every macro —
     * classify this, shorten that, pull the code out of this message.
     */
    FAST,

    /** Reasons before answering, and takes noticeably longer for it. */
    BALANCED,

    /** The most capable available. Slow enough to be worth choosing deliberately. */
    THOROUGH,
}

/**
 * One question put to a model.
 *
 * [systemInstruction] is separate from [prompt] rather than being pasted on the
 * front of it, because the two are separate fields on the wire and providers treat
 * them differently — a standing instruction is not one more thing the user said.
 * Blank means "none given", which is the ordinary case.
 *
 * [maxOutputTokens] is a *bound and not a target*: it exists so a runaway answer
 * cannot cost a user their whole quota in one macro run, which matters here in a
 * way it does not in a chat app — a macro can fire on a trigger, unattended, all
 * day. A reply cut short by it is reported rather than silently truncated.
 */
data class AiRequest(
    /**
     * Which saved way of asking to send through — an
     * [io.github.m1n1m1.easymatic.domain.model.AiModelProfile] id.
     *
     * **One field where there used to be two.** A node named a connection *and* an
     * [AiModel] tier, which meant a macro was choosing between three trade-offs and
     * nothing else about the model: the persona and the model id were bound elsewhere
     * and the tool permissions nowhere at all. A profile carries all four, so a node
     * points at one id and everything about how that model behaves is edited in one
     * place — including for macros written months earlier.
     *
     * Carried on the request rather than bound into the facade, which is
     * [SmartHome]'s shape for [SmartHome]'s reason: one instance serves the whole
     * process, and the profile is resolved on every call so that a key replaced or a
     * model retargeted mid-run is what the next prompt uses. Blank is not a default —
     * it is a node with nothing chosen, and the facade says so rather than picking one.
     */
    val modelRef: String,
    val prompt: String,
    val systemInstruction: String = "",
    val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
    /**
     * Pictures to look at alongside [prompt].
     *
     * A list rather than one, because "which of these two photos has the parcel in
     * it" is a question people ask — and because every provider here takes an array
     * anyway, so the singular form would be the special case.
     *
     * Defaulted empty, which is what makes this invisible to every existing caller and
     * every existing test: a request with no images renders exactly the body it did
     * before this field existed.
     */
    val images: List<AiImage> = emptyList(),
    /**
     * Sound to listen to alongside [prompt].
     *
     * A list for [images]' symmetry rather than because two clips are useful — every
     * provider that takes audio takes an array, so the singular form would be the
     * special case, and the two media fields reading alike is worth more than the one
     * element saved.
     *
     * **A blank [prompt] beside a non-empty list is a legitimate request**, and it is
     * the one place this field changes a rule rather than adding one: it means "just
     * transcribe this", which is both the commonest thing to want and the thing a
     * transcription endpoint takes literally. `RoutingAi` therefore refuses a blank
     * prompt only when there is no audio either.
     *
     * Defaulted empty, on [images]' reasoning.
     */
    val audio: List<AiAudio> = emptyList(),
) {
    companion object {
        /**
         * Roughly a page and a half of prose. Generous for the things a macro
         * legitimately asks for — a summary, a classification, a rewritten
         * sentence — and low enough that a prompt which accidentally asks for a
         * novel stops rather than billing for one.
         */
        const val DEFAULT_MAX_OUTPUT_TOKENS = 1_024
    }
}

/**
 * One picture, ready for the wire.
 *
 * **Base64 rather than bytes**, matching [Files.readBytes] and for its reason: this is
 * the form all three providers want, so bytes would be encoded again at the point of
 * use and held twice in a foreground service's heap.
 *
 * [mediaType] is required rather than sniffed. Every provider here demands it
 * explicitly, and guessing it from the bytes would put an image decoder in `core/`
 * for a fact the file's own name already carries.
 */
data class AiImage(
    val base64: String,
    val mediaType: String,
)

/**
 * One sound, ready for the wire.
 *
 * [AiImage]'s shape and its reasons — Base64 because that is the form every provider
 * wants, and an explicit [mediaType] because every one of them demands it.
 *
 * **It is a separate type from [AiImage] because the two are refused differently.**
 * Every provider here that sees pictures at all accepts the same four picture types,
 * so an image's media type never decides whether a request is sendable. Sound is the
 * opposite: Gemini takes wav, mp3, aiff, aac, ogg and flac but not `audio/mp4`;
 * OpenAI's chat wire takes wav and mp3 and nothing else; a transcription endpoint
 * takes almost anything; and Claude takes none of it. `AiProtocol.audioProblem` is
 * what reads this field to say so before the network, and it needs a field it can be
 * sure is sound.
 */
data class AiAudio(
    val base64: String,
    val mediaType: String,
)

/**
 * How much sound may be sent to a model in one request.
 *
 * **Four megabytes, and the number is a heap budget rather than a provider's limit.**
 * Gemini's own bound is 20 MB on the whole request, which is measured *after* Base64
 * inflates the bytes by a third — but the binding constraint here is nearer home:
 * this is read inside `MacroEngineService`, which is holding every armed macro on the
 * phone, and a file of this size is simultaneously a `ByteArray`, a Base64 `String`
 * (UTF-16, so twice again) and the request body. Four megabytes is about seventeen
 * minutes of the app's own voice recording, which is far past anything a macro
 * sensibly asks a model about, and it keeps that peak survivable.
 *
 * Over it the file is **refused with a sentence naming the size**, never truncated —
 * `readBoundedBase64`'s rule, for its reason: half a recording is not a recording.
 */
object AudioLimits {
    const val MAX_MODEL_BYTES: Int = 4 * 1024 * 1024
}

/**
 * What a model answered, or why it did not.
 *
 * [text] is the reply as text and nothing else — no envelope, no citations, no
 * token counts. That is deliberate: the node has a single Text output, and
 * anything richer would have to arrive as a struct the user then has to
 * `action.break` apart before reading the one field they wanted.
 *
 * [error] blank means the model answered. A non-blank [error] always comes with a
 * blank [text]: there is no half-answer, and a caller that had to tell "failed"
 * from "answered nothing" could do nothing different about either.
 *
 * [truncated] is the one thing worth reporting *alongside* a successful answer,
 * and it is why this is not simply a `String?`. A reply stopped at
 * [AiRequest.maxOutputTokens] is real output that a macro will happily go on and
 * send to somebody, so the run log has to be able to say it was cut off — which
 * an empty error field and a full [text] would never reveal.
 */
data class AiReply(
    val text: String = "",
    val error: String = "",
    val truncated: Boolean = false,
    /**
     * Something that happened on the way to this answer which the run log should record,
     * or blank when nothing did.
     *
     * **[truncated]'s argument, one step further.** That field exists because a reply cut
     * off at the token bound is real output a macro will happily send on, so the console
     * has to be able to say it was cut off — an empty [error] and a full [text] would
     * never reveal it. This is the same shape for the same reason: a request that could
     * not run on the phone and was answered by its profile's fallback **succeeded**, and
     * every field above says so, but which model answered has changed and so has who is
     * being billed. An unattended macro quietly moving from a free local model to a paid
     * cloud one is exactly what the run log exists to record.
     *
     * A **sentence rather than a flag**, unlike [truncated], because there is no fixed
     * set of things to say: the reason the phone could not answer is one of six, and the
     * node has nothing to add to any of them. Defaulted blank, which is what makes it
     * invisible to every existing protocol and every existing test.
     */
    val note: String = "",
)

/**
 * The [Ai] a phone with no API key set up has.
 *
 * Answers the same shape a refused key does, so the node needs no branch for
 * "never configured" — but says which of the two it is, because the fix differs
 * and "it did not work" would send the user looking in the wrong place.
 */
object NoAi : Ai {

    override suspend fun complete(request: AiRequest): AiReply = AiReply(error = UNAVAILABLE)

    override suspend fun converse(
        request: AiRequest,
        tools: List<AiTool>,
        maxTurns: Int,
        invoke: suspend (AiToolCall) -> AiToolResult,
    ): AiReply = AiReply(error = UNAVAILABLE)

    override suspend fun toolsFor(modelRef: String): String = ""

    private const val UNAVAILABLE =
        "No AI connection is set up on this phone — add one under AI in the menu on the macro list"
}
