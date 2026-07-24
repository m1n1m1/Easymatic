package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.SystemState
import com.example.ottomatic.engine.NodeOutput
import kotlinx.coroutines.flow.Flow

/**
 * Trigger for `trigger.screen`. Fires when the screen turns on or off.
 *
 * Produced by the runtime-registered
 * [com.example.ottomatic.data.trigger.ScreenBroadcastBridge]. Produces a typed
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
