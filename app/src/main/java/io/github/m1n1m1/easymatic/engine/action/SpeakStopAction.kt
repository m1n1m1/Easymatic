package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.NoConfig
import io.github.m1n1m1.easymatic.engine.Action
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.effectNode

/**
 * `action.speak_stop` — stops whatever is being spoken, and pulses `out`.
 *
 * `action.stop_sounds`' counterpart for the voice, and it exists for the same reason that one
 * does: an `action.speak` with "Wait until finished" switched off leaves an utterance running
 * with **nothing that will ever end it early**. Without this, a macro that starts a long
 * sentence and then decides it was wrong has no way to take it back, and the only remedy is
 * waiting the sentence out.
 *
 * It is what makes the fire-and-forget option safe to offer at all, which is why the two
 * arrived together rather than this being added when somebody complained.
 *
 * **No config**, on `action.stop_sounds`' reasoning: there is one voice, so there is nothing
 * to name. Stopping also empties the queue, because a queue that survived a stop would start
 * the next sentence the instant this one was cut off — which is not what anybody means by
 * stop.
 *
 * A caller waiting on the utterance this ends is **released rather than cancelled**: the
 * speech stops and that macro carries on from its `out` port. Cancelling it would make one
 * macro able to kill another's run, which nothing else in the app can do.
 */
class SpeakStopAction : Action<NoConfig, Unit> {

    override val definition = effectNode<NoConfig>(
        typeId = "action.speak_stop",
        displayName = "Stop Speaking",
        description = "Stops anything the phone is currently saying out loud",
        category = NodeCategory.INTERACTION,
        icon = NodeIcon.SPEAK,
        capabilities = NEEDS_VOICE,
    )

    override suspend fun execute(input: NoConfig, context: ExecutionContext): NodeOutput<Unit> {
        val stopped = context.speech.stop()
        // Reaching a stop with nothing speaking is worth a line rather than silence: a graph
        // that gets here usually ran in an order its author did not expect. It is not a
        // warning, though — the macro is in exactly the state the node asked for.
        context.log(if (stopped) "Stopped speaking" else "Nothing was being said")
        return NodeOutput(Unit)
    }
}
