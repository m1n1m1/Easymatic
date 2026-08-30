package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.items.SystemState
import io.github.m1n1m1.easymatic.engine.NodeOutput
import kotlinx.coroutines.flow.Flow

/**
 * Trigger for `trigger.screen`. Fires when the screen turns on or off.
 *
 * Produced by the runtime-registered
 * [io.github.m1n1m1.easymatic.data.trigger.ScreenBroadcastBridge]. Produces a typed
 * [SystemState] item on the `state` data port.
 */
class ScreenTrigger : Trigger<EventFilter<OnOffEvent>, SystemState> {

    override val definition = systemStateDefinition<EventFilter<OnOffEvent>>(
        typeId = "trigger.screen",
        displayName = "Screen On / Off",
        description = "Starts when the screen turns on or off",
        category = NodeCategory.DEVICE_STATE,
    )

    override fun activate(
        config: EventFilter<OnOffEvent>,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<SystemState>> = systemStateFlow(
        source = TriggerSource.DISPLAY,
        triggerType = "screen",
        host = host,
        event = config.event,
    )
}
