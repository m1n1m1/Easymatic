package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.items.SystemState
import io.github.m1n1m1.easymatic.engine.NodeOutput
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
