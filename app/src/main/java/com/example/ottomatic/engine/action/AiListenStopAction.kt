package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.AiRequest
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
 * Config for `action.ai_listen_stop`.
 *
 * **The whole AI half of the pair lives here**, because this is where the answer comes
 * out: the model that is billed, the question asked of it, the reply bound and what lands
 * on the port when any of it fails. `action.ai_listen_start` carries only the two numbers
 * that bound the listening itself.
 *
 * [prompt] blank means transcribe, exactly as it does on `action.ai_listen` and
 * `action.ai_transcribe` — one rule across the three nodes that take sound.
 */
@Serializable
data class AiListenStopConfig(
    @Label("Model") @Picker(PickerKind.AI_MODEL) val modelRef: String = "",
    @Label("What to ask (leave empty to transcribe it)") @Multiline @Wired val prompt: String = "",
    @Label("Longest reply (tokens)") val maxOutputTokens: Int = AiRequest.DEFAULT_MAX_OUTPUT_TOKENS,
    @Label("If it fails") @Multiline val fallback: String = "",
)

/**
 * `action.ai_listen_stop` — ends the listening and puts the model's answer on a port.
 *
 * **`action.record_stop`'s place in its family**, and it takes that node's contract: the
 * clip belongs to whoever collects it, and reaching a stop nobody started is worth
 * reporting rather than treating as a silent no-op — a macro doing that is usually a graph
 * that ran in an order its author did not expect.
 *
 * It also collects a capture that **ended on its own limit**, which is the case worth
 * knowing about: `action.ai_listen_start` releases the microphone when its span runs out
 * but keeps the sound, so a macro whose stop node is reached a minute late still gets the
 * minute that was recorded rather than silence.
 *
 * Everything after the clip — a quiet room landing on the fallback with an INFO line, a
 * refused model landing on it with an ERROR, a truncated reply reaching the port with a
 * warning — is `askAbout`, shared with `action.ai_listen` so the two cannot come to
 * disagree about what silence means.
 *
 * It declares `RECORD_AUDIO` even though it opens nothing: the pair is one capability from
 * the user's point of view, and a stop node that looked grant-free would let the
 * Permissions screen imply half of a two-node flow works without the microphone.
 */
class AiListenStopAction : Action<AiListenStopConfig, String> {

    override val definition = actionNode<AiListenStopConfig, String>(
        typeId = "action.ai_listen_stop",
        displayName = "Stop Listening with AI",
        description = "Stops the microphone opened by Start Listening with AI and sends what it heard " +
            "to an AI model to transcribe, or to answer a question about what was said",
        category = NodeCategory.AI,
        icon = NodeIcon.MICROPHONE,
        output = dataOut<String>("answer", label = "Answer"),
        permissions = listOf(RECORD_AUDIO),
    )

    override suspend fun execute(input: AiListenStopConfig, context: ExecutionContext): NodeOutput<String> =
        context.askAbout(
            clip = context.microphone.endCapture(),
            node = "Stop Listening with AI",
            ask = AiAsk(input.modelRef, input.prompt, input.maxOutputTokens, input.fallback),
        )
}
