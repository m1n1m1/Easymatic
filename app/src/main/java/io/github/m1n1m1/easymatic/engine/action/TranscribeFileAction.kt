package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.AiAudio
import io.github.m1n1m1.easymatic.core.service.AiRequest
import io.github.m1n1m1.easymatic.core.service.AudioLimits
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.FilePath
import io.github.m1n1m1.easymatic.domain.model.config.Hint
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Multiline
import io.github.m1n1m1.easymatic.domain.model.config.Picker
import io.github.m1n1m1.easymatic.domain.model.config.PickerKind
import io.github.m1n1m1.easymatic.domain.model.config.Wired
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.actionNode
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
data class TranscribeFileConfig(
    @Label("Model") @Picker(PickerKind.AI_MODEL) val modelRef: String = "",
    @Label("Audio file") @FilePath @Wired val audio: String = "",
    @Label("What to ask")
    @Hint("leave empty to transcribe it")
    @Multiline @Wired val prompt: String = "",
    @Label("Longest reply")
    @Hint("tokens")
    val maxOutputTokens: Int = AiRequest.DEFAULT_MAX_OUTPUT_TOKENS,
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
 * A failure lands on [TranscribeFileConfig.fallback] and still pulses `out`, which is
 * `action.script`'s contract for its reason.
 */
class TranscribeFileAction : Action<TranscribeFileConfig, String> {

    override val definition = actionNode<TranscribeFileConfig, String>(
        typeId = "action.transcribe_file",
        displayName = "Transcribe File",
        description = "Sends a sound file to an AI model and returns the transcript, " +
            "or the answer to a question about what was said",
        category = NodeCategory.AI,
        icon = NodeIcon.AI,
        output = dataOut<String>("answer", label = "Answer"),
    )

    @Suppress("ReturnCount") // No file, an unreadable one, an unknown kind, a refused answer — four sentences.
    override suspend fun execute(input: TranscribeFileConfig, context: ExecutionContext): NodeOutput<String> {
        if (input.audio.isBlank()) {
            context.log("Transcribe File: no sound file chosen", LogLevel.ERROR)
            return NodeOutput(input.fallback)
        }
        val clip = context.files.readBytes(input.audio, AudioLimits.MAX_MODEL_BYTES)
        if (clip.error.isNotBlank()) {
            context.log("Transcribe File: ${clip.error}", LogLevel.ERROR)
            return NodeOutput(input.fallback)
        }
        if (clip.mediaType.isBlank()) {
            // Refused here rather than sent with a guessed type, because a wrong one
            // comes back as a 400 naming neither the file nor the reason — and the
            // extension is the only thing anybody can actually change.
            context.log(
                "Transcribe File: \"${input.audio}\" does not end in a sound file extension " +
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
            context.log("Transcribe File failed: ${reply.error}", LogLevel.ERROR)
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
