package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.SystemState
import com.example.ottomatic.engine.NodeOutput
import kotlinx.coroutines.flow.Flow

/**
 * Trigger for `trigger.power_save`. Fires when power-save mode is toggled.
 *
 * Produces a typed [SystemState] item on the `state` data port.
 */
class PowerSaveTrigger : Trigger<EventFilter<OnOffEvent>, SystemState> {

    override val definition = systemStateDefinition<EventFilter<OnOffEvent>>(
        typeId = "trigger.power_save",
        displayName = "Power Save Mode Changed",
        description = "Starts when power-save mode is toggled on or off",
        category = NodeCategory.POWER_BATTERY,
    )

    override fun activate(
        config: EventFilter<OnOffEvent>,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<SystemState>> = systemStateFlow(
        source = TriggerSource.DISPLAY,
        triggerType = "power_save",
        host = host,
        event = config.event,
    )
}
