package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.SystemState
import com.example.ottomatic.engine.NodeOutput
import kotlinx.coroutines.flow.Flow

/**
 * Trigger for `trigger.time_tick`. Fires roughly every minute
 * (ACTION_TIME_TICK). Produced by the runtime-registered
 * [com.example.ottomatic.data.trigger.ScreenBroadcastBridge].
 *
 * Produces a typed [SystemState] item on the `state` data port.
 */
class TimeTickTrigger : Trigger<SystemState> {

    override val definition = systemStateDefinition(
        typeId = "trigger.time_tick",
        displayName = "Regular Time Tick",
        description = "Fires roughly every minute while the device is awake",
        category = NodeCategory.TIME_SCHEDULE,
        iconKey = "timer",
    )

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<SystemState>> =
        systemStateFlow(
            source = TriggerSource.SYSTEM,
            triggerType = "time_tick",
            node = node,
            host = host,
        )
}
