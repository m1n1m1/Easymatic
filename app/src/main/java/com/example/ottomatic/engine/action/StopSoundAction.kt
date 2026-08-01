package com.example.ottomatic.engine.action

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.NoConfig
import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.effectNode

/**
 * Action for `action.stop_sound`. Silences every sound `action.play_sound`
 * started, including one another workflow is waiting on — that workflow is
 * released and carries on rather than being cancelled.
 *
 * It has nothing to configure: sounds are not addressable individually, and
 * "stop the noise" is the whole of what anyone wants from it.
 */
class StopSoundAction : Action<NoConfig, Unit> {

    override val definition = effectNode<NoConfig>(
        typeId = "action.stop_sound",
        displayName = "Stop Sound",
        description = "Stops every sound started by Play Sound",
        category = NodeCategory.NOTIFICATIONS,
        icon = NodeIcon.MUSIC_OFF,
    )

    override suspend fun execute(input: NoConfig, context: ExecutionContext): NodeOutput<Unit> {
        val stopped = context.systemServices.stopSounds()
        if (stopped > 0) context.log("Stopped $stopped sound(s)")
        return NodeOutput(Unit)
    }
}
