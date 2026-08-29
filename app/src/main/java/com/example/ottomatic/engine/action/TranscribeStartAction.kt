package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.CaptureRequest
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.core.service.AiRequest
import com.example.ottomatic.domain.model.config.Hint
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Multiline
import com.example.ottomatic.domain.model.config.Picker
import com.example.ottomatic.domain.model.config.PickerKind
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.config.Suggested
import com.example.ottomatic.domain.model.config.SuggestionSource
import com.example.ottomatic.domain.model.config.VisibleWhen
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.effectNode
import kotlinx.serialization.Serializable

/**
 * Config for `action.transcribe_start`.
 *
 * **The whole pair is set up here, and that is a correction.** The engine lived on this
 * node and the model on End Transcribing, each where it was *used* — which is true of the
 * code and useless to the person filling the form in: one decision, split across two cards,
 * with no way to tell from either which half you were looking at. Worse, End asked for a
 * model even when the phone was doing the work, because a static form cannot know what
 * Start chose.
 *
 * `action.record_start` and `action.record_stop` already answer this: every field is on the
 * start node and the stop node takes `NoConfig`. A session is one thing to set up and one
 * thing to collect. What End keeps is the one field that is genuinely about End — what to
 * put on its port if the whole thing fails.
 *
 * The model and question reach End through `PendingTranscription`.
 *
 * [maxSeconds] is a **limit rather than a length**, which is `action.record_start`'s rule
 * for its reason, sharpened: a capture nobody stops holds the microphone with the system's
 * indicator lit and, unlike a recording, has no file growing on disk to make it visible.
 * It is not optional and the facade clamps it.
 *
 * [silenceSeconds] defaults to **0 — do not stop early** — which is the opposite default
 * from `action.ai_listen`. There, a fixed span is the whole node and quiet is the natural
 * way to end it early; here the macro has said explicitly that it will decide when to
 * stop, so ending on a pause would take the decision back.
 */
@Serializable
data class TranscribeStartConfig(
    @Label("Transcribe with") val using: TranscribeUsing = TranscribeUsing.AI,
    @Label("Model") @Picker(PickerKind.AI_MODEL) @VisibleWhen("using", "ai") val modelRef: String = "",
    @Label("What to ask")
    @Hint("leave empty to transcribe it")
    @Multiline @Wired @VisibleWhen("using", "ai")
    val prompt: String = "",
    @Label("Longest reply")
    @Hint("tokens")
    @VisibleWhen("using", "ai")
    val maxOutputTokens: Int = AiRequest.DEFAULT_MAX_OUTPUT_TOKENS,
    @Label("Language") @VisibleWhen("using", "phone")
    val languageMode: TranscribeLanguage = TranscribeLanguage.PHONE,
    @Label("Which language")
    @Suggested(SuggestionSource.RECOGNITION_LANGUAGE)
    @VisibleWhen("languageMode", "chosen")
    val language: String = "",
    @Label("Listen for at most")
    @Hint("seconds")
    val maxSeconds: Int = 60,
    @Label("Stop after this much silence")
    @Hint("seconds, 0 = listen the whole time")
    val silenceSeconds: Int = 0,
)

/**
 * `action.ai_listen_start` — opens the microphone and returns at once.
 *
 * **`action.record_start` for a clip that is never a file**, and it exists for that node's
 * reason: `action.ai_listen` blocks for its whole span, so a macro cannot listen *while*
 * doing something else — announce a prompt, wait for a tap, watch for a trigger — and then
 * ask about what it heard. Splitting the two calls is what makes "start listening, do
 * things, stop and ask" a graph anybody can draw.
 *
 * The sound goes nowhere until `action.ai_listen_stop` collects it. **A clip whose own
 * limit ran out is kept rather than thrown away**, so a macro that listened for its full
 * minute still has something for its stop node — the microphone is released either way,
 * which is what stops a forgotten capture holding the hardware.
 *
 * There is one microphone, so a start while a recording or another capture is running
 * reports that and pulses `out` rather than taking the hardware away from whatever has it.
 * That is `Microphone`'s stated rule and the only behaviour that leaves the running macro
 * working.
 *
 * It pulses `out` whatever happened, on `action.record_start`'s contract: a microphone is
 * unusually easy to be refused, and none of the ways is a reason for a macro to stop.
 */
class TranscribeStartAction : Action<TranscribeStartConfig, Unit> {

    override val definition = effectNode<TranscribeStartConfig>(
        typeId = "action.transcribe_start",
        displayName = "Start Transcribing",
        description = "Opens the microphone and keeps listening until End Transcribing turns " +
            "what was said into text",
        category = NodeCategory.AI,
        icon = NodeIcon.MICROPHONE,
        permissions = listOf(RECORD_AUDIO),
    )

    override suspend fun execute(input: TranscribeStartConfig, context: ExecutionContext): NodeOutput<Unit> {
        // **The engine is chosen here and not at the End node**, which is the one thing
        // about this pair that could have gone either way. It has to be: the choice decides
        // which hardware session opens, and by the time End runs the audio has already been
        // captured one way or the other. End therefore reads no engine field and simply
        // collects whichever session this one started.
        // Remembered before anything opens, and cleared for the phone engine, so a later End
        // cannot find a model left over from a previous run of a different macro.
        PendingTranscription.remember(
            if (input.using == TranscribeUsing.AI) {
                AiAsk(input.modelRef, input.prompt, input.maxOutputTokens, fallback = "")
            } else {
                null
            },
        )
        val problem = if (input.using == TranscribeUsing.PHONE) {
            context.speech.beginListening(
                listenRequestFor(
                    input.languageMode,
                    input.language,
                    input.maxSeconds,
                    input.silenceSeconds,
                ),
            )
        } else {
            context.microphone.beginCapture(
                CaptureRequest(maxSeconds = input.maxSeconds, silenceSeconds = input.silenceSeconds),
            )
        }
        if (problem.isNotBlank()) {
            context.log("Start Transcribing: $problem", LogLevel.ERROR)
        } else {
            context.log("Transcribing for up to ${input.maxSeconds}s", LogLevel.DEBUG)
        }
        return NodeOutput(Unit)
    }
}
