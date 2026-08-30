package io.github.m1n1m1.easymatic.engine.value

import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.NoConfig
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.ValueNode
import io.github.m1n1m1.easymatic.engine.valueNode

/**
 * `value.speaking` — whether the phone is saying something right now.
 *
 * **The value half of the speaking family**, and [RecordingValue]'s twin in both shape and
 * argument. An `action.speak` with "Wait until finished" switched off outlives the run that
 * queued it, so without this there is no way to ask the one question every graph using that
 * option needs answered: "am I already talking?". "When a message arrives, **if** nothing is
 * being said, read it out" is one comparison because of this node, and a variable somebody
 * has to remember to keep in step without it.
 *
 * **As cheap as the pull side gets.** It does not leave the process: the flag is set when this
 * app queues an utterance and cleared when the engine reports it finished, so "cheap,
 * repeatable and cannot fail" is a description of what it is rather than a judgement about it.
 * Notably it is *not* `TextToSpeech.isSpeaking`, which lags an enqueue by however long the
 * engine takes to start — reading that would answer false immediately after a `action.speak`
 * and make a guard against talking over yourself guard against nothing.
 *
 * **No capability declared, and refusing to declare one is the point.** A node whose entire
 * job is to report whether something is speaking must never be badged for the phone having no
 * voice — `value.nfc`'s argument, and here it is sharper still: with no engine at all the
 * honest answer to "is it speaking?" is `false`, which is exactly what this returns. It is the
 * two actions that should wear the warning.
 *
 * **Never null**, on [RecordingValue]'s reasoning: there is no state in which this cannot be
 * read, which is what lets a comparison over it mean what it says rather than failing closed
 * to something the user has to reason about.
 */
class SpeakingValue : ValueNode<NoConfig, Boolean> {

    override val definition = valueNode<NoConfig, Boolean>(
        typeId = "value.speaking",
        displayName = "Speaking",
        description = "Whether the phone is saying something out loud right now",
        category = NodeCategory.VALUE_DEVICE,
        icon = NodeIcon.SPEAK,
        output = dataOut("speaking", label = "Speaking"),
    )

    override suspend fun read(config: NoConfig, context: ExecutionContext): Boolean =
        context.speech.isSpeaking()
}
