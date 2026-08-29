package com.example.ottomatic.data.ai

import com.example.ottomatic.core.service.AiImage
import com.example.ottomatic.core.service.AiModel
import com.example.ottomatic.core.service.AiReply
import com.example.ottomatic.core.service.AiRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * A model that answers on this phone, with no wire and no key.
 *
 * **The seam sits here rather than at [AiProtocol], and that is the one structural
 * decision this file exists to record.** Every one of that interface's six abstract
 * members is HTTP-shaped — `endpoint`, `modelsEndpoint`, `headers`, `requestBody`,
 * `readReply`, `readModels` — because the five providers behind it differ only in their
 * wire format, which is exactly what it abstracts. An on-device model has none of those
 * things. Implementing that interface for it would be six lies in a file whose whole
 * value is that it is pure and JVM-tested, so `RoutingAi` branches one level up instead
 * and [protocolFor] answers null for the providers served here.
 *
 * **Suspending, and nothing throws** — [AiProtocol]'s and
 * [com.example.ottomatic.core.service.Ai]'s rule carried across unchanged: a device with
 * no AICore, a model not yet downloaded and a generation that failed all reach the caller
 * as an ordinary value, because the caller has one thing to do with all of them.
 */
internal interface OnDeviceAi {

    /** Whether this phone can run the model right now. */
    suspend fun status(): OnDeviceStatus

    /** Answers [plan], or reports in [AiReply.error] why it could not. */
    suspend fun complete(plan: MlKitPlan): AiReply

    /** Fetches the model weights, reporting progress. Only ever started from the AI screen. */
    fun download(): Flow<OnDeviceDownload>

    /** What the device calls the model it would use, or blank when it cannot be asked. */
    suspend fun baseModelName(): String

    /**
     * Drops any cached [status], so the next question asks the device again.
     *
     * Exists because [status] is consulted on every tool-enabled node run and is an
     * inter-process round trip; the cache is what keeps that cheap, and this is the only
     * way anything that changes the answer — a finished download, a return from Settings —
     * can say so.
     */
    fun forget()
}

/**
 * What a phone can do about the on-device model, mirroring ML Kit's own four states.
 *
 * **Four rather than a boolean**, because three of them have different fixes and the
 * fourth has none: an unsupported phone is a fact about the hardware, a downloadable
 * model is one tap in AI settings, and a downloading one is a matter of waiting. Folding
 * them would put "this phone cannot run it" in front of somebody whose download is at
 * ninety per cent.
 */
enum class OnDeviceStatus {
    /** Ready to answer. */
    AVAILABLE,

    /** Supported, but the weights are not on the phone yet. */
    DOWNLOADABLE,

    /** The weights are being fetched now. */
    DOWNLOADING,

    /**
     * This phone cannot run it at all — no AICore, no supported chip, or an unlocked
     * bootloader. Also what a failed status query answers, because a question that could
     * not be asked and a "no" lead to the same place here: the fallback.
     */
    UNSUPPORTED,
}

/**
 * How a model download is going.
 *
 * Public, with [OnDeviceStatus], because the AI settings screen draws both — the same
 * split `AiModels` and `AiModelInfo` already have beside an internal [AiProtocol]: the
 * *vocabulary* an editor renders is public, the engine that produces it is not.
 */
sealed interface OnDeviceDownload {

    /** The fetch has begun; [totalBytes] is zero when the device did not say how large it is. */
    data class Started(val totalBytes: Long) : OnDeviceDownload

    /** [downloadedBytes] of [totalBytes] so far; [totalBytes] zero means unknown. */
    data class Progress(val downloadedBytes: Long, val totalBytes: Long) : OnDeviceDownload

    data object Done : OnDeviceDownload

    /** Gave up, with the sentence to show. */
    data class Failed(val error: String) : OnDeviceDownload
}

/**
 * The [OnDeviceAi] of a build that has no on-device engine wired in.
 *
 * Answers [OnDeviceStatus.UNSUPPORTED], which routes every request to its profile's
 * fallback exactly as an unsupported phone does — so an engine-only test needs no ML Kit
 * on its classpath, which is [com.example.ottomatic.core.service.NoAi]'s reason for
 * existing one level up.
 */
internal object NoOnDeviceAi : OnDeviceAi {

    override suspend fun status(): OnDeviceStatus = OnDeviceStatus.UNSUPPORTED

    override suspend fun complete(plan: MlKitPlan): AiReply = AiReply(error = UNSUPPORTED_TEXT)

    override fun download(): Flow<OnDeviceDownload> = flowOf(OnDeviceDownload.Failed(UNSUPPORTED_TEXT))

    override suspend fun baseModelName(): String = ""

    override fun forget() = Unit

    private const val UNSUPPORTED_TEXT = "This phone has no on-device AI"
}

/**
 * One question put to the on-device model, in the shape its request builder takes.
 *
 * **A plan rather than the [AiRequest] itself**, for the reason `AiProtocol.requestBody`
 * returns a string rather than taking one: building it is the part worth testing, and it
 * needs the profile and the tier, which the request does not carry. Everything here is
 * decided by [mlKitPlan] with no device present, so the whole mapping is a JVM test and
 * the Android half has nothing left to decide.
 *
 * [image] is one picture rather than a list, and that is ML Kit's own bound rather than a
 * simplification — its request builder takes at most one. A request carrying more is
 * refused by [onDeviceProblem] before it reaches here, so this field never silently drops
 * the second one.
 */
internal data class MlKitPlan(
    val prompt: String,
    val systemInstruction: String,
    val image: AiImage?,
    val maxOutputTokens: Int,
    val temperature: Float,
    val topK: Int,
    /** Whether to ask AICore for the fuller model rather than the quicker one. */
    val preferFullModel: Boolean,
)

/**
 * The plan [request] becomes for [target].
 *
 * **The tier buys the model and nothing else here**, which is worth stating because the
 * cloud protocols make it buy considerably more. There, [AiModel] chooses a published
 * model id *and* a reasoning budget — Gemini's `thinkingLevel`, OpenAI's
 * `reasoning_effort`, Anthropic's `thinking` block. On this phone there is one model
 * family and no reasoning knob at all, so the honest mapping is [AiModel.FAST] to the
 * quick variant and both slower tiers to the full one. Inventing a temperature ladder to
 * make three tiers feel different would be a knob pretending to be a trade-off, and a
 * macro wants a reproducible answer far more than a varied one — hence one low
 * [TEMPERATURE] for every tier.
 *
 * **No headroom is added to [AiRequest.maxOutputTokens]**, and that is deliberate rather
 * than an omission: `GeminiProtocol` and `AnthropicProtocol` both add a
 * `thinkingHeadroom` because thinking tokens come out of the same budget as the reply
 * there, so a 200-token request can return no text at all. ML Kit has no such field, so
 * the bound means the reply. Do not add one.
 */
internal fun mlKitPlan(request: AiRequest, target: AiTarget): MlKitPlan = MlKitPlan(
    // The shared reading of the field rather than the raw one, so this path can never
    // disagree with the three chat renderers about what a request says. Its transcription
    // branch is unreachable from here — sound is refused by `onDeviceProblem` before a
    // plan is built — and it is used anyway, because a second reading of one field is a
    // second reading that drifts.
    prompt = chatPrompt(request),
    systemInstruction = request.systemInstruction,
    image = request.images.firstOrNull(),
    maxOutputTokens = request.maxOutputTokens.coerceAtLeast(1),
    temperature = TEMPERATURE,
    topK = TOP_K,
    preferFullModel = target.effort != AiModel.FAST,
)

/**
 * Why [request] cannot run on this phone, or null when it can.
 *
 * **One function holding every pre-flight reason, so they cannot drift and each keeps its
 * own sentence.** This is `audioWireFor`'s shape and its reasoning: the decision is worth
 * a JVM test, it needs no device, and splitting it across the router and the engine is how
 * two of these eventually disagree. Every non-null answer sends the request to the
 * profile's fallback, so the sentence is what the user sees only when there is none.
 *
 * **It is deliberately not the whole story.** A prompt past the model's input bound, an
 * AICore that dies mid-generation and whatever a beta SDK does next are not knowable here,
 * so `RoutingAi` also falls back on an on-device *failure*. That asymmetry with the cloud
 * providers — where a runtime failure is reported rather than retried elsewhere — is sound
 * rather than sloppy: a cloud retry spends somebody's quota on a request that may already
 * have been billed, where an on-device attempt costs nothing at all, so trying and then
 * asking the fallback is strictly better than reporting.
 */
@Suppress("ReturnCount") // Six distinct reasons, each with the sentence that names its fix;
// folding them is what loses the diagnosis this function exists to give.
internal fun onDeviceProblem(
    request: AiRequest,
    status: OnDeviceStatus,
    wantsTools: Boolean,
): String? {
    if (status == OnDeviceStatus.UNSUPPORTED) return NOT_SUPPORTED
    if (status == OnDeviceStatus.DOWNLOADABLE) return NOT_DOWNLOADED
    if (status == OnDeviceStatus.DOWNLOADING) return STILL_DOWNLOADING
    // Checked before the media bounds, because a tool-using node fails this whatever it
    // carries, and "it cannot use tools" is the sentence that names what to change.
    if (wantsTools) return NO_TOOLS
    if (request.audio.isNotEmpty()) return NO_AUDIO
    if (request.images.size > 1) return ONE_PICTURE
    return null
}

/**
 * Low rather than the API's own default, and the same for every tier.
 *
 * A macro asks a model to classify, shorten or extract, then wires the answer into
 * something that acts on it. Varied phrasing is a cost there rather than a feature.
 */
private const val TEMPERATURE = 0.2f

private const val TOP_K = 16

private const val NOT_SUPPORTED =
    "This phone cannot run AI on the device — it needs a phone with AICore, such as a " +
        "Pixel 9 or a Galaxy S25 or newer"

private const val NOT_DOWNLOADED =
    "The on-device model has not been downloaded yet — open AI settings and download it"

private const val STILL_DOWNLOADING = "The on-device model is still downloading"

private const val NO_TOOLS =
    "The on-device model cannot use tools — turn \"Let it use tools\" off, or give this " +
        "model a fallback in AI settings"

private const val NO_AUDIO =
    "The on-device model cannot listen to audio — choose a Gemini, OpenAI, OpenRouter or " +
        "self-hosted model"

private const val ONE_PICTURE = "The on-device model can only look at one picture at a time"

/**
 * What the AI settings screen needs of the on-device model, and nothing a macro does.
 *
 * **A second class over the same engine holding the editor's needs**, exactly as
 * [AiModelCatalog] is over the connection library and for its reason: no macro ever
 * downloads a model or asks what this phone is called, and `RoutingAi` must never grow a
 * method that starts a multi-gigabyte download. It is also what keeps [OnDeviceAi] itself
 * internal — a public class may not take an internal type in a public constructor, and
 * the honest fix is a narrower public surface rather than a wider internal one.
 */
class OnDeviceSetup internal constructor(private val engine: OnDeviceAi) {

    /** Whether this phone can run the model right now. */
    suspend fun status(): OnDeviceStatus = engine.status()

    /** What the device calls the model it would use, or blank when it cannot be asked. */
    suspend fun modelName(): String = engine.baseModelName()

    /**
     * Fetches the model weights, reporting progress.
     *
     * **The only caller is a button somebody pressed**, which is the whole reason this is
     * here and not on the engine's macro-facing side: the weights are large enough to be
     * a metered-data decision, and an unattended macro that started one at three in the
     * morning would be spending somebody's allowance on their behalf. A macro that meets
     * an undownloaded model takes its fallback and says so instead.
     */
    fun download(): Flow<OnDeviceDownload> = engine.download()

    /** Asks the phone again next time — after a download, or after a trip to Settings. */
    fun forget() = engine.forget()
}
