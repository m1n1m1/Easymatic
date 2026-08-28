package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.AiAudio
import com.example.ottomatic.core.service.AiRequest
import com.example.ottomatic.core.service.CaptureOutcome
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput

/**
 * The half `action.ai_listen` and `action.ai_listen_stop` share: a clip, then a model.
 *
 * **Written once because the two nodes differ only in where the sound came from.** One
 * listens for a fixed span and asks; the other collects a span somebody else started and
 * asks. Everything after "here is a clip" — the quiet room, the refused model, the
 * truncated reply, the fallback — is the same contract, and two copies of it is how the
 * two nodes end up disagreeing about what silence means.
 *
 * [RecordingSupport]'s shape, one family along.
 */
@Suppress("ReturnCount") // A refused microphone, a quiet room, a refused model and an
// answer each say their own thing; folding any two loses the sentence the node reports.
internal suspend fun ExecutionContext.askAbout(
    clip: CaptureOutcome,
    node: String,
    ask: AiAsk,
): NodeOutput<String> {
    if (clip.error.isNotBlank()) {
        log("$node: ${clip.error}", LogLevel.ERROR)
        return NodeOutput(ask.fallback)
    }
    if (!clip.heard) {
        // INFO rather than WARN: a quiet room is the node working, and a macro that
        // listens every hour would otherwise fill the console with warnings at night.
        log("$node: nothing was said", LogLevel.INFO)
        return NodeOutput(ask.fallback)
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
        return NodeOutput(ask.fallback)
    }
    if (reply.truncated) {
        log("The reply was cut off at ${ask.maxOutputTokens} tokens — raise the reply limit", LogLevel.WARN)
    }
    return NodeOutput(reply.text)
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
