package com.example.ottomatic.engine.action

import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.service.AiAudio
import com.example.ottomatic.core.service.AiRequest
import com.example.ottomatic.core.service.CaptureOutcome
import com.example.ottomatic.core.service.ListenOutcome
import com.example.ottomatic.core.service.ListenRequest
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the four transcription nodes share: which engine does the work, and what happens
 * to a clip once one of them has it.
 *
 * **Written once because the nodes differ only in where the sound came from.** One
 * listens for a fixed span; one collects a span somebody else started; one reads a file.
 * Everything after "here is a clip" — the quiet room, the refused model, the truncated
 * reply, the fallback — is the same contract, and copies of it are how the nodes end up
 * disagreeing about what silence means.
 *
 * `RecordingSupport`'s shape, one family along.
 */

/**
 * Which engine turns the sound into words.
 *
 * **A choice worth putting on the card, rather than plumbing to hide.** The rule against
 * making a user pick between two platform mechanisms is about mechanisms that answer the
 * same question equally well; these do not. One is billed, needs a key and a network, and
 * can answer a question *about* what was said; the other is free, runs on the phone, works
 * with no signal, and can only ever give the words back. Nothing in the code can choose
 * between those on the user's behalf, because the right answer depends on what they are
 * willing to pay and what leaves the phone.
 *
 * `SoundSource`'s shape — an enum that decides which of the fields below it apply — and
 * `@VisibleWhen` is what keeps the form from showing both sets at once.
 */
@Serializable
enum class TranscribeUsing {

    /** A model from the AI library. Can answer a question; costs a request. */
    @SerialName("ai")
    @Label("An AI model")
    AI,

    /**
     * The phone's own speech recogniser — the same one `action.listen` uses.
     *
     * Free, no key, and offline where the language pack is installed. It only ever
     * transcribes, so the question and reply-limit fields are hidden when it is chosen.
     */
    @SerialName("phone")
    @Label("This phone, offline")
    PHONE,
}

/**
 * Which language the phone's recogniser should expect.
 *
 * **Three named answers rather than a text box whose blank meant something.** The field was
 * a language tag where empty meant "the phone's own language" — which made empty both the
 * default *and* the "I do not know" answer, and left the thing most people actually want
 * unexpressible. A mode says which of the three is meant, and only one of them needs a
 * value, which is `PlaySoundConfig`'s `sound` plus its conditional `uri` exactly.
 */
@Serializable
enum class TranscribeLanguage {

    /** What the blank field used to mean, now saying so. */
    @SerialName("phone")
    @Label("The phone's language")
    PHONE,

    /**
     * Let the recogniser work it out and switch to it.
     *
     * Android 14 and above, and only where the recogniser implements it — elsewhere this
     * behaves as [PHONE], which is what the field did before the option existed.
     */
    @SerialName("detect")
    @Label("Detect automatically")
    DETECT,

    /** A tag named in the field below. */
    @SerialName("chosen")
    @Label("A language I choose")
    CHOSEN,
}

/**
 * Transcribes [request] with the phone's own recogniser and lands it on the answer port.
 *
 * The offline twin of [askAbout], sharing its rules exactly: a quiet room is INFO and not
 * an error, anything the user can act on is ERROR, and both land on the fallback and pulse
 * `out`. What it cannot share is the request shape — a recogniser takes a language and a
 * span where a model takes a prompt and a token budget — so the two are separate functions
 * over one contract rather than one function with a mode flag.
 */
internal suspend fun ExecutionContext.transcribeOnPhone(
    outcome: ListenOutcome,
    node: String,
    fallback: String,
): NodeOutput<Map<PortName, Item>> = when {
    outcome.heard -> {
        // The detected tag is named in the log as well as put on the port, because it is
        // the one thing that tells a wrong transcription from a rightly-transcribed wrong
        // language — and a macro that ran unattended has only the console to say which.
        log("Heard '${outcome.text}'" + outcome.language.ifBlank { "" }.let { if (it.isBlank()) "" else " ($it)" })
        NodeOutput(transcriptPorts(outcome.text, outcome.language))
    }

    outcome.timedOut -> {
        log("$node: nobody finished speaking in time", LogLevel.INFO)
        NodeOutput(transcriptPorts(fallback, ""))
    }

    outcome.error.isNotBlank() -> {
        log("$node: ${outcome.error}", LogLevel.ERROR)
        NodeOutput(transcriptPorts(fallback, ""))
    }

    else -> {
        log("$node: nothing was said", LogLevel.INFO)
        NodeOutput(transcriptPorts(fallback, ""))
    }
}

/** The DATA output carrying the words. */
internal val TRANSCRIPT_OUT = PortName("answer")

/** The DATA output carrying the BCP-47 tag the recogniser said it used. */
internal val TRANSCRIPT_LANGUAGE_OUT = PortName("language")

/**
 * The two output ports, with [language] **omitted when blank** rather than sent empty.
 *
 * `action.listen`'s rule: a port nobody can answer carries nothing rather than an empty
 * string, so a node wired from two branches cannot read a fabricated value. Nothing reports
 * a language on the AI path — the models do not say what they detected — and nothing
 * reports one below Android 14, so that port is genuinely unset far more often than not.
 */
internal fun transcriptPorts(text: String, language: String): Map<PortName, Item> = buildMap {
    put(TRANSCRIPT_OUT, Item(text, ItemSchema.Primitive(String::class)))
    if (language.isNotBlank()) {
        put(TRANSCRIPT_LANGUAGE_OUT, Item(language, ItemSchema.Primitive(String::class)))
    }
}

/** The two ports both answering nodes declare, so they cannot come to differ. */
internal fun transcriptPortsDeclaration() = listOf(
    Port(TRANSCRIPT_OUT, PortKind.DATA, Direction.OUT, ItemSchema.Primitive(String::class), label = "Answer"),
    Port(
        TRANSCRIPT_LANGUAGE_OUT,
        PortKind.DATA,
        Direction.OUT,
        ItemSchema.Primitive(String::class),
        label = "Language",
    ),
)
@Suppress("ReturnCount") // A refused microphone, a quiet room, a refused model and an
// answer each say their own thing; folding any two loses the sentence the node reports.
internal suspend fun ExecutionContext.askAbout(
    clip: CaptureOutcome,
    node: String,
    ask: AiAsk,
): NodeOutput<Map<PortName, Item>> {
    if (clip.error.isNotBlank()) {
        log("$node: ${clip.error}", LogLevel.ERROR)
        return NodeOutput(transcriptPorts(ask.fallback, ""))
    }
    if (!clip.heard) {
        // INFO rather than WARN: a quiet room is the node working, and a macro that
        // listens every hour would otherwise fill the console with warnings at night.
        log("$node: nothing was said", LogLevel.INFO)
        return NodeOutput(transcriptPorts(ask.fallback, ""))
    }
    log("Heard ${clip.durationMs} ms of audio", LogLevel.DEBUG)

    val reply = ai.complete(
        AiRequest(
            modelRef = ask.modelRef,
            // Passed through blank on purpose: the blank *is* the instruction to
            // transcribe, and substituting a default here would send every plain
            // transcription down the chat wire instead of the endpoint built for it.
            prompt = ask.prompt,
            maxOutputTokens = ask.maxOutputTokens,
            audio = listOf(AiAudio(base64 = clip.base64, mediaType = clip.mediaType)),
        ),
    )
    if (reply.error.isNotBlank()) {
        log("$node failed: ${reply.error}", LogLevel.ERROR)
        return NodeOutput(transcriptPorts(ask.fallback, ""))
    }
    if (reply.truncated) {
        log("The reply was cut off at ${ask.maxOutputTokens} tokens — raise the reply limit", LogLevel.WARN)
    }
    // No language port on this path: a model does not report what it detected.
    return NodeOutput(transcriptPorts(reply.text, ""))
}

/**
 * The four fields both listening nodes put to a model.
 *
 * A holder rather than four more parameters, on `RecordingRequest`'s stated reasoning:
 * two nodes pass the same thing, and a positional list they share is a list they can come
 * to disagree about. It is not a config class — each node keeps its own, because their
 * other fields differ — so this is the shape they agree on rather than a shape either owns.
 */
internal data class AiAsk(
    val modelRef: String,
    val prompt: String,
    val maxOutputTokens: Int,
    val fallback: String,
)

/** The recogniser request both microphone nodes build, so the two cannot drift. */
internal fun listenRequestFor(
    mode: TranscribeLanguage,
    language: String,
    maxSeconds: Int,
    silenceSeconds: Int,
) =
    ListenRequest(
        // Only CHOSEN names one. PHONE and DETECT both send no language: the first so the
        // recogniser uses the phone's own, the second because the switch starts from that
        // same default and moves off it.
        language = if (mode == TranscribeLanguage.CHOSEN) language else "",
        detect = mode == TranscribeLanguage.DETECT,
        // "0 = listen the whole time" on these nodes, where the same zero means "let the
        // phone decide" on `action.listen`. Saying which is meant here is the whole point
        // of the flag being separate from the number.
        continuous = silenceSeconds <= 0,
        // Always: the whole point of choosing this engine is that nothing leaves the phone.
        // It stays a *preference* because the facade falls back rather than refusing, which
        // is `ListenRequest.preferOffline`'s own stated reasoning.
        preferOffline = true,
        maxSeconds = maxSeconds,
        silenceSeconds = silenceSeconds,
    )

/**
 * What Start Transcribing decided, waiting for End Transcribing to collect it.
 *
 * **The whole pair is configured on Start, and this is what makes that possible.** Which
 * engine opens, which model is billed and what it is asked are one decision the user makes
 * once; splitting them across two cards — the engine on the node that opens the microphone,
 * the model on the node that sends the audio — put half a thought on each and made the End
 * node ask for a model it does not need whenever the phone was doing the work.
 *
 * `action.record_start` and `action.record_stop` already have this shape and for the same
 * reason: every field lives on the start node and the stop node has `NoConfig`, because a
 * session is one thing to set up and one thing to collect.
 *
 * **A plain singleton, because there is exactly one session.** That is not a simplification
 * but the same fact `Microphone` states — there is one microphone — so a map keyed by
 * anything would have one entry and invent the idea that it might not. `PendingWaits` is the
 * precedent for engine-level state a node writes and another reads.
 *
 * Losing this to process death is harmless: the capture dies with it, so End reports that
 * nothing is running either way.
 */
internal object PendingTranscription {

    @Volatile
    private var ask: AiAsk? = null

    /** Remembers what Start chose, or clears it when Start chose the phone. */
    fun remember(ask: AiAsk?) {
        this.ask = ask
    }

    /** Takes what Start chose, leaving nothing behind for a later End to find. */
    fun take(): AiAsk? {
        val held = ask
        ask = null
        return held
    }
}
