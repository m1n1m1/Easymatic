package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.NoConfig
import com.example.ottomatic.domain.model.items.SystemState
import com.example.ottomatic.engine.NodeOutput
import kotlinx.coroutines.flow.Flow

/**
 * Trigger for `trigger.time_tick`. Fires roughly every minute while the device
 * is awake (ACTION_TIME_TICK).
 *
 * Produces a typed [SystemState] item on the `state` data port.
 */
class TimeTickTrigger : Trigger<NoConfig, SystemState> {

    override val definition = systemStateDefinition<NoConfig>(
        typeId = "trigger.time_tick",
        displayName = "Regular Time Tick",
        description = "Fires roughly every minute while the device is awake",
        category = NodeCategory.TIME_SCHEDULE,
        icon = NodeIcon.TIMER,
    )

    override fun activate(
        config: NoConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<SystemState>> = systemStateFlow(
        source = TriggerSource.SYSTEM,
        triggerType = "time_tick",
        host = host,
    )
}
