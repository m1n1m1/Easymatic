package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.AiRequest
import com.example.ottomatic.core.service.CaptureRequest
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Multiline
import com.example.ottomatic.domain.model.config.Picker
import com.example.ottomatic.domain.model.config.PickerKind
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.actionNode
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
data class AiListenConfig(
    @Label("Model") @Picker(PickerKind.AI_MODEL) val modelRef: String = "",
    @Label("What to ask (leave empty to transcribe it)") @Multiline @Wired val prompt: String = "",
    @Label("Listen for at most (seconds)") val maxSeconds: Int = 15,
    @Label("Stop after this much silence (seconds, 0 = listen the whole time)")
    val silenceSeconds: Int = 3,
    @Label("Longest reply (tokens)") val maxOutputTokens: Int = AiRequest.DEFAULT_MAX_OUTPUT_TOKENS,
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
class AiListenAction : Action<AiListenConfig, String> {

    override val definition = actionNode<AiListenConfig, String>(
        typeId = "action.ai_listen",
        displayName = "Listen with AI",
        // "transcribe" rather than "transcript", and that is the palette's business
        // rather than prose: `matchesSearch` is a substring match over the description,
        // and "transcript" does not contain "transcribe" — so the word somebody actually
        // types has to appear in the word this node uses. `DialogDiscoverabilityTest`'s
        // rule, one family along.
        description = "Listens through the microphone and sends what it heard to an AI model " +
            "to transcribe, or to answer a question about what was said",
        category = NodeCategory.AI,
        icon = NodeIcon.MICROPHONE,
        output = dataOut<String>("answer", label = "Answer"),
        permissions = listOf(RECORD_AUDIO),
    )

    override suspend fun execute(input: AiListenConfig, context: ExecutionContext): NodeOutput<String> {
        val clip = context.microphone.capture(
            CaptureRequest(maxSeconds = input.maxSeconds, silenceSeconds = input.silenceSeconds),
        )
        return context.askAbout(
            clip = clip,
            node = "Listen with AI",
            ask = AiAsk(input.modelRef, input.prompt, input.maxOutputTokens, input.fallback),
        )
    }
}
