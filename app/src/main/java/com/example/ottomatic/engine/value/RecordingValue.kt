package com.example.ottomatic.engine.value

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.NoConfig
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.ValueNode
import com.example.ottomatic.engine.valueNode

/**
 * `value.recording` — whether a recording is running right now.
 *
 * **The value half of the recording family**, and the state the actions leave behind. A
 * recording started by `action.record_start` outlives the run that started it, so without
 * this there is no way to ask the one question every graph using that node needs answered:
 * "am I already recording?". "When I get in the car, **if** nothing is recording, start"
 * is one comparison because of this node, and a variable somebody has to remember to keep
 * in step without it.
 *
 * **The cheapest read on the pull side, by some distance.** `value.calendar_busy` justified
 * itself with local IPC to a provider and `value.latest_image` with one indexed cursor
 * query; this does not leave the process at all. It is a flag this app set when it started
 * the recording — so "cheap, repeatable and cannot fail" is not a judgement about it but a
 * description of what it is.
 *
 * **No permission declared, and refusing to declare one is the point.** A node whose entire
 * job is to report whether something is on must never be badged for it being off, and the
 * failure here would be sharper than `value.nfc`'s: this reads a boolean that is *always*
 * correct, grant or no grant. With `RECORD_AUDIO` refused, the honest answer to "is a
 * recording running?" is `false`, and it is the actions that should be wearing the warning.
 *
 * **Never null.** A value node answers null when it cannot read, and there is no such state
 * here — which is what lets a comparison over it mean what it says rather than failing
 * closed to something the user has to reason about.
 */
class RecordingValue : ValueNode<NoConfig, Boolean> {

    override val definition = valueNode<NoConfig, Boolean>(
        typeId = "value.recording",
        displayName = "Recording",
        description = "Whether the microphone is recording right now",
        category = NodeCategory.VALUE_DEVICE,
        icon = NodeIcon.MICROPHONE,
        output = dataOut("recording", label = "Recording"),
    )

    override suspend fun read(config: NoConfig, context: ExecutionContext): Boolean =
        context.microphone.isRecording()
}
