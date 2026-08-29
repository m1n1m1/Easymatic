package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.AiRequest
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Multiline
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.engine.NodeInput
import com.example.ottomatic.engine.RawAction
import com.example.ottomatic.engine.effectNode
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
data class TranscribeEndConfig(
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
class TranscribeEndAction : RawAction<TranscribeEndConfig> {

    override val definition = effectNode<TranscribeEndConfig>(
        typeId = "action.transcribe_end",
        displayName = "End Transcribing",
        description = "Ends the microphone opened by Start Transcribing and turns what was said " +
            "into text, with an AI model or on the phone itself",
        category = NodeCategory.AI,
        icon = NodeIcon.MICROPHONE,
        // The same two ports Transcribe declares, from the same function — a pair of nodes
        // whose outputs drifted apart would be worse than either shape.
        extraPorts = transcriptPortsDeclaration(),
        permissions = listOf(RECORD_AUDIO),
    )

    override suspend fun executeRaw(
        config: TranscribeEndConfig,
        input: NodeInput,
        context: ExecutionContext,
    ): NodeOutput<Map<PortName, Item>> {
        // **Which engine ran is discovered rather than configured**, and that is what lets
        // this node carry no engine field at all. Start Transcribing opened either a
        // recogniser session or a recording; asking which is open cannot disagree with what
        // Start chose, where a second dropdown here could and would.
        if (context.speech.isTranscribing()) {
            PendingTranscription.take()
            return context.transcribeOnPhone(context.speech.endListening(), NAME, config.fallback)
        }
        // What to ask was decided on Start, where the user chose the engine — see
        // `PendingTranscription`. A missing one means End was reached without a Start, which
        // `endCapture` is about to report in its own words.
        val ask = PendingTranscription.take() ?: AiAsk("", "", AiRequest.DEFAULT_MAX_OUTPUT_TOKENS, "")
        return context.askAbout(
            clip = context.microphone.endCapture(),
            node = NAME,
            ask = ask.copy(fallback = config.fallback),
        )
    }

    private companion object {
        const val NAME = "End Transcribing"
    }
}
