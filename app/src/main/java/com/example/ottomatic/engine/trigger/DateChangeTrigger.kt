package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.NoConfig
import com.example.ottomatic.domain.model.items.SystemState
import com.example.ottomatic.engine.NodeOutput
import kotlinx.coroutines.flow.Flow

/**
 * Trigger for `trigger.date_change`. Fires when the device date changes
 * (ACTION_DATE_CHANGED).
 *
 * Produces a typed [SystemState] item on the `state` data port.
 */
class DateChangeTrigger : Trigger<NoConfig, SystemState> {

    override val definition = systemStateDefinition<NoConfig>(
        typeId = "trigger.date_change",
        displayName = "Date Changed",
        description = "Starts when the device date changes (midnight rollover)",
        category = NodeCategory.TIME_SCHEDULE,
    )

    override fun activate(
        config: NoConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<SystemState>> = systemStateFlow(
        source = TriggerSource.SYSTEM,
        triggerType = "date_change",
        host = host,
    )
}
