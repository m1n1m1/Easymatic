package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.AiAudio
import com.example.ottomatic.core.service.AiRequest
import com.example.ottomatic.core.service.AudioLimits
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.FilePath
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
 * Config for `action.ai_transcribe`.
 *
 * [audio] is `@FilePath` and `@Wired` for the reason every file node's path is, and
 * more sharply here than for `action.ai_describe`: the recording worth transcribing is
 * almost never one somebody typed the name of — it is the one `action.record_audio`
 * just made, or the one `trigger.recording_saved` just announced, so the commonest
 * shape is a wire from that node's own result.
 *
 * **[prompt] blank is not an omission, it is the setting.** Leaving it empty means "just
 * transcribe it", which is what most macros want and what a transcription endpoint takes
 * literally; filling it in turns the node into a question about the recording, answered
 * by a chat model. That is the whole of the user-visible choice, and the two wires
 * behind it are `AiProtocol`'s business rather than the user's — the rule this app
 * follows everywhere: one field, resolved in code.
 */
@Serializable
data class AiTranscribeConfig(
    @Label("Model") @Picker(PickerKind.AI_MODEL) val modelRef: String = "",
    @Label("Audio file") @FilePath @Wired val audio: String = "",
    @Label("What to ask (leave empty to transcribe it)") @Multiline @Wired val prompt: String = "",
    @Label("Longest reply (tokens)") val maxOutputTokens: Int = AiRequest.DEFAULT_MAX_OUTPUT_TOKENS,
    @Label("If it fails") @Multiline val fallback: String = "",
)

/**
 * `action.ai_transcribe` — turns a recording into text, or answers a question about one.
 *
 * **The join the app was missing.** A macro could already record a voice note and could
 * already ask a model about a photo; it could not ask a model about the voice note. This
 * is the node that closes that, and it is deliberately the same shape as
 * `action.ai_describe` — the same first field, the same fallback, the same truncation
 * rule — because they answer the same kind of question about two kinds of media.
 *
 * **Where it differs from `action.ai_describe` is that the media type decides
 * everything.** Every provider that sees pictures accepts the same four picture types,
 * so that node can hand any image to any model and let the server object. Sound is not
 * like that: Claude cannot hear at all, Gemini refuses `audio/mp4` — which is what this
 * app's own recorder writes — and OpenAI's chat wire takes WAV and MP3 and nothing else,
 * while its transcription endpoint takes almost anything. So the refusals are worded in
 * `AiProtocol.audioProblem` and arrive **before** the network, naming the format and the
 * fix. That is `readBoundedBase64`'s rule about a picture of an unknown kind, applied to
 * a family where it is the common case rather than the edge one.
 *
 * **[AudioLimits.MAX_MODEL_BYTES] rather than the file layer's default**, because this
 * is the one caller that knows what it is holding. Four megabytes is roughly seventeen
 * minutes of the app's own voice recording; over it the file is refused with a sentence
 * naming the size rather than truncated, since half a recording transcribes into a
 * sentence that stops mid-word and looks like the model's own failure.
 *
 * No tool switch, on `action.ai_describe`'s reasoning exactly: `conversationBody` renders
 * a turn as text on all three protocols, so media reaches a model through `complete` and
 * not through the tool loop.
 *
 * A failure lands on [AiTranscribeConfig.fallback] and still pulses `out`, which is
 * `action.script`'s contract for its reason.
 */
class AiTranscribeAction : Action<AiTranscribeConfig, String> {

    override val definition = actionNode<AiTranscribeConfig, String>(
        typeId = "action.ai_transcribe",
        displayName = "Transcribe Audio with AI",
        description = "Sends a sound file to an AI model and returns the transcript, " +
            "or the answer to a question about what was said",
        category = NodeCategory.AI,
        icon = NodeIcon.AI,
        output = dataOut<String>("answer", label = "Answer"),
    )

    @Suppress("ReturnCount") // No file, an unreadable one, an unknown kind, a refused answer — four sentences.
    override suspend fun execute(input: AiTranscribeConfig, context: ExecutionContext): NodeOutput<String> {
        if (input.audio.isBlank()) {
            context.log("Transcribe Audio with AI: no sound file chosen", LogLevel.ERROR)
            return NodeOutput(input.fallback)
        }
        val clip = context.files.readBytes(input.audio, AudioLimits.MAX_MODEL_BYTES)
        if (clip.error.isNotBlank()) {
            context.log("Transcribe Audio with AI: ${clip.error}", LogLevel.ERROR)
            return NodeOutput(input.fallback)
        }
        if (clip.mediaType.isBlank()) {
            // Refused here rather than sent with a guessed type, because a wrong one
            // comes back as a 400 naming neither the file nor the reason — and the
            // extension is the only thing anybody can actually change.
            context.log(
                "Transcribe Audio with AI: \"${input.audio}\" does not end in a sound file extension " +
                    "this app recognises — try .wav, .mp3, .m4a, .ogg or .flac",
                LogLevel.ERROR,
            )
            return NodeOutput(input.fallback)
        }

        val reply = context.ai.complete(
            AiRequest(
                modelRef = input.modelRef,
                // Passed through blank on purpose: the blank *is* the instruction to
                // transcribe, and substituting a default here would send every plain
                // transcription down the chat wire instead of the endpoint built for it.
                prompt = input.prompt,
                maxOutputTokens = input.maxOutputTokens,
                audio = listOf(AiAudio(base64 = clip.base64, mediaType = clip.mediaType)),
            ),
        )
        if (reply.error.isNotBlank()) {
            context.log("Transcribe Audio with AI failed: ${reply.error}", LogLevel.ERROR)
            return NodeOutput(input.fallback)
        }
        if (reply.truncated) {
            context.log(
                "The transcript was cut off at ${input.maxOutputTokens} tokens — raise the reply limit",
                LogLevel.WARN,
            )
        }
        return NodeOutput(reply.text)
    }
}
