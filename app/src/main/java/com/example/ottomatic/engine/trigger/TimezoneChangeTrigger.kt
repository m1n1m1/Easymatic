package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.NoConfig
import com.example.ottomatic.domain.model.items.SystemState
import com.example.ottomatic.engine.NodeOutput
import kotlinx.coroutines.flow.Flow

/**
 * Trigger for `trigger.timezone_change`. Fires when the device timezone
 * changes; the new zone id arrives in [SystemState.detail].
 *
 * Produces a typed [SystemState] item on the `state` data port.
 */
class TimezoneChangeTrigger : Trigger<NoConfig, SystemState> {

    override val definition = systemStateDefinition<NoConfig>(
        typeId = "trigger.timezone_change",
        displayName = "Timezone Changed",
        description = "Starts when the device timezone changes",
        category = NodeCategory.TIME_SCHEDULE,
    )

    override fun activate(
        config: NoConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<SystemState>> = systemStateFlow(
        source = TriggerSource.SYSTEM,
        triggerType = "timezone_change",
        host = host,
    )
}
