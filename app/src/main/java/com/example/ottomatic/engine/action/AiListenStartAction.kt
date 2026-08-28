package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.CaptureRequest
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.effectNode
import kotlinx.serialization.Serializable

/**
 * Config for `action.ai_listen_start`.
 *
 * **It holds no model and no question, and that is where the pair divides.** Nothing is
 * asked of a model here — this node only opens the microphone — so putting a **Model**
 * field on it would be asking a question at the moment there is nothing yet to ask about,
 * and would leave two model fields on one pair of nodes to disagree with each other. The
 * model, the question and the fallback all live on `action.ai_listen_stop`, which is where
 * the answer comes out.
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
data class AiListenStartConfig(
    @Label("Listen for at most (seconds)") val maxSeconds: Int = 60,
    @Label("Stop after this much silence (seconds, 0 = listen the whole time)")
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
class AiListenStartAction : Action<AiListenStartConfig, Unit> {

    override val definition = effectNode<AiListenStartConfig>(
        typeId = "action.ai_listen_start",
        displayName = "Start Listening with AI",
        // Names what the pair is *for* rather than only what this half does, because the
        // palette searches descriptions: somebody typing "transcribe" is looking for the
        // whole family, and a start node that never says the word is one they cannot find.
        description = "Opens the microphone and keeps listening until Stop Listening with AI " +
            "transcribes it or asks a question about what was said",
        category = NodeCategory.AI,
        icon = NodeIcon.MICROPHONE,
        permissions = listOf(RECORD_AUDIO),
    )

    override suspend fun execute(input: AiListenStartConfig, context: ExecutionContext): NodeOutput<Unit> {
        val problem = context.microphone.beginCapture(
            CaptureRequest(maxSeconds = input.maxSeconds, silenceSeconds = input.silenceSeconds),
        )
        if (problem.isNotBlank()) {
            context.log("Start Listening with AI: $problem", LogLevel.ERROR)
        } else {
            context.log("Listening for up to ${input.maxSeconds}s", LogLevel.DEBUG)
        }
        return NodeOutput(Unit)
    }
}
