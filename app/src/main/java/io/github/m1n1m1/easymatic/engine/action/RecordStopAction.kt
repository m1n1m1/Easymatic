package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.NoConfig
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.RecordingResultItem
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.actionNode

/**
 * `action.record_stop` — ends the running recording and hands over the file.
 *
 * **No config at all, because there is only ever one recording to stop.** There is one
 * microphone, so a field naming which recording to end would be a question with exactly one
 * possible answer — and worse, it would suggest there could be two. That is also what makes
 * the node useful across macros: whichever graph started the recording, this ends it.
 *
 * **Stopping nothing is reported rather than ignored.** It answers `changed = false` with a
 * sentence, because a macro that reached a stop it never started is nearly always a graph
 * whose branches ran in an order its author did not expect — and a silent no-op there leaves
 * somebody looking for a bug in the microphone.
 *
 * It still pulses `out` either way, on the family's rule: nothing here halts a macro.
 *
 * A recording may also end without this node — its own time limit, or the engine shutting
 * down — which is why `trigger.recording_saved` exists. Both endings run the same code and
 * produce the same file; the only difference is who hears about it.
 */
class RecordStopAction : Action<NoConfig, RecordingResultItem> {

    override val definition = actionNode<NoConfig, RecordingResultItem>(
        typeId = "action.record_stop",
        displayName = "Stop Recording",
        description = "Ends the recording that is running and saves it as a file",
        category = NodeCategory.AUDIO,
        icon = NodeIcon.MICROPHONE,
        output = dataOut<RecordingResultItem>("state", label = "Result"),
        permissions = listOf(RECORD_AUDIO),
    )

    override suspend fun execute(
        input: NoConfig,
        context: ExecutionContext,
    ): NodeOutput<RecordingResultItem> =
        NodeOutput(context.reportRecording(context.microphone.stop(), "Recorded"))
}
