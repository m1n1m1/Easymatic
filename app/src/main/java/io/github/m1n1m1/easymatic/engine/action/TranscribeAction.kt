package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.AiRequest
import io.github.m1n1m1.easymatic.core.service.CaptureRequest
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.Hint
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Multiline
import io.github.m1n1m1.easymatic.domain.model.config.Picker
import io.github.m1n1m1.easymatic.domain.model.config.PickerKind
import io.github.m1n1m1.easymatic.domain.model.config.Suggested
import io.github.m1n1m1.easymatic.domain.model.config.SuggestionSource
import io.github.m1n1m1.easymatic.domain.model.config.VisibleWhen
import io.github.m1n1m1.easymatic.domain.model.config.Wired
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.core.model.PortName
import io.github.m1n1m1.easymatic.domain.model.schema.Item
import io.github.m1n1m1.easymatic.engine.NodeInput
import io.github.m1n1m1.easymatic.engine.RawAction
import io.github.m1n1m1.easymatic.engine.effectNode
import kotlinx.serialization.Serializable

/**
 * Config for `action.ai_listen`.
 *
 * [maxSeconds] is not optional and has no "forever" value, on `action.listen`'s
 * argument: an open microphone holds the hardware with the system's indicator lit and,
 * unlike `action.record_start`, has no file growing on disk to make that visible. Here
 * it does a second job — 16 kHz mono PCM is thirty-two kilobytes a second, so the number
 * of seconds is also the heap this node costs — and the facade clamps whatever it is
 * given.
 *
 * **[silenceSeconds] means the opposite of what the same field means on
 * `action.listen`, and the label has to say so.** There, `0` hands end-of-speech
 * detection to the platform, which is far better at telling a pause from a finished
 * sentence than any number a user could pick. There is no platform detector behind a raw
 * microphone read, so `0` here can only mean "do not stop early". A field whose zero
 * quietly meant one thing on one node and the reverse on the next would be a lie with no
 * way for the user to catch it.
 *
 * **[prompt] blank is the setting, not an omission** — `action.ai_transcribe`'s field
 * exactly, and for its reason.
 */
@Serializable
data class TranscribeConfig(
    @Label("Transcribe with") val using: TranscribeUsing = TranscribeUsing.AI,
    @Label("Model") @Picker(PickerKind.AI_MODEL) @VisibleWhen("using", "ai") val modelRef: String = "",
    @Label("What to ask")
    @Hint("leave empty to transcribe it")
    @Multiline @Wired @VisibleWhen("using", "ai")
    val prompt: String = "",
    @Label("Language") @VisibleWhen("using", "phone")
    val languageMode: TranscribeLanguage = TranscribeLanguage.PHONE,
    @Label("Which language")
    @Suggested(SuggestionSource.RECOGNITION_LANGUAGE)
    @VisibleWhen("languageMode", "chosen")
    val language: String = "",
    @Label("Listen for at most")
    @Hint("seconds")
    val maxSeconds: Int = 15,
    @Label("Stop after this much silence")
    @Hint("seconds, 0 = listen the whole time")
    val silenceSeconds: Int = 3,
    @Label("Longest reply")
    @Hint("tokens")
    @VisibleWhen("using", "ai")
    val maxOutputTokens: Int = AiRequest.DEFAULT_MAX_OUTPUT_TOKENS,
    @Label("If it fails") @Multiline val fallback: String = "",
)

/**
 * `action.ai_listen` — hears something and hands it to a model.
 *
 * **`action.listen` with a different pair of ears, and the difference is the whole
 * point.** That node uses the platform's `SpeechRecognizer`: free, on-device where a
 * model exists, and bounded by whichever languages this particular phone shipped with.
 * This one sends the sound itself to a model, which costs a network round trip and buys
 * three things that recogniser cannot do — any language the model knows, punctuation and
 * speaker sense rather than a bare word sequence, and an *answer* instead of a
 * transcript. "What did they say?" is `action.listen`; "was that a yes?" is this one.
 *
 * **It records nothing to disk**, which is why it exists as a node rather than as a
 * documented pairing of `action.record_audio` and `action.ai_transcribe`. Those two
 * compose perfectly and leave a file behind on every run, which a macro firing on a
 * schedule turns into a folder nobody asked for; `Microphone.capture` holds the clip in
 * memory for the length of one request and then does not.
 *
 * **The clip is WAV, and that is forced.** `action.record_audio` writes MPEG-4/AAC —
 * right for a file people attach to a mail, and refused by Gemini's inline set and by
 * OpenAI's chat wire alike. Mono 16 kHz PCM is the one format every provider here
 * accepts, so this node works on every connection that can hear at all, which is the
 * property a live-listen node most needs.
 *
 * **It suspends in place rather than forking.** `Microphone.record`'s reasoning exactly:
 * "listen, then send what you heard" is written top to bottom, and a fork would put the
 * sending on a branch for no reason the user asked for. The mandatory cap is what makes
 * that safe — a fork is for a wait that may never end.
 *
 * **Hearing nothing is not a failure.** A macro that listens on a schedule and finds the
 * room quiet has worked exactly as asked, so that lands on the fallback with an INFO
 * line rather than an error in red. `action.listen` draws the same line with its
 * `nothing` port.
 *
 * The microphone grant is the recording family's `RECORD_AUDIO` rather than a second
 * constant: it is the same capability from the user's point of view, and the Permissions
 * screen keys on the rationale — a fresh one is exactly how that page ends up with two
 * rows for one thing. It declares **no** `DeviceCapability`: there is no recogniser here
 * that can be absent, so `action.listen`'s `SPEECH_RECOGNITION` would badge this node in
 * the Problems panel on a phone that can run it perfectly.
 */
class TranscribeAction : RawAction<TranscribeConfig> {

    override val definition = effectNode<TranscribeConfig>(
        typeId = "action.transcribe",
        displayName = "Transcribe",
        description = "Listens through the microphone and transcribes what was said — with an AI " +
            "model, which can also answer a question about it, or on the phone itself for free",
        category = NodeCategory.AI,
        icon = NodeIcon.MICROPHONE,
        // Two DATA outputs, so a `RawAction` with `extraPorts` rather than a typed
        // `actionNode` — `action.listen`'s shape, and the only one the builders offer for
        // more than one output. The `answer` port keeps its name and schema, so wires drawn
        // before the second port existed still resolve.
        extraPorts = transcriptPortsDeclaration(),
        permissions = listOf(RECORD_AUDIO),
    )

    override suspend fun executeRaw(
        config: TranscribeConfig,
        input: NodeInput,
        context: ExecutionContext,
    ): NodeOutput<Map<PortName, Item>> {
        // The two engines take the microphone by different routes and neither can be
        // expressed in terms of the other: a recogniser listens and answers words, where a
        // model needs a recording handed to it. So the branch is here, at the top, rather
        // than inside a facade pretending they are one thing.
        if (config.using == TranscribeUsing.PHONE) {
            val heard = context.speech.listen(
                listenRequestFor(
                    config.languageMode,
                    config.language,
                    config.maxSeconds,
                    config.silenceSeconds,
                ),
            )
            return context.transcribeOnPhone(heard, NAME, config.fallback)
        }
        val clip = context.microphone.capture(
            CaptureRequest(maxSeconds = config.maxSeconds, silenceSeconds = config.silenceSeconds),
        )
        return context.askAbout(
            clip = clip,
            node = NAME,
            ask = AiAsk(config.modelRef, config.prompt, config.maxOutputTokens, config.fallback),
        )
    }

    private companion object {
        const val NAME = "Transcribe"
    }
}
