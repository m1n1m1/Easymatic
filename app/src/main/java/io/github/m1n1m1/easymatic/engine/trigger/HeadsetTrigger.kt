package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.items.SystemState
import io.github.m1n1m1.easymatic.engine.NodeOutput
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** The headset transitions `trigger.headset` can filter on. */
@Serializable
enum class HeadsetEvent {
    PLUGGED,
    UNPLUGGED,
}

/**
 * Trigger for `trigger.headset`. Fires when a wired headset is plugged or
 * unplugged.
 *
 * Produces a typed [SystemState] item on the `state` data port.
 */
class HeadsetTrigger : Trigger<EventFilter<HeadsetEvent>, SystemState> {

    override val definition = systemStateDefinition<EventFilter<HeadsetEvent>>(
        typeId = "trigger.headset",
        displayName = "Headset Plugged",
        description = "Starts when a wired headset is plugged or unplugged",
        category = NodeCategory.CONNECTIVITY,
    )

    override fun activate(
        config: EventFilter<HeadsetEvent>,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<SystemState>> = systemStateFlow(
        source = TriggerSource.HARDWARE,
        triggerType = "headset",
        host = host,
        event = config.event,
    )
}
