package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.SystemState
import com.example.ottomatic.engine.NodeOutput
import kotlinx.coroutines.flow.Flow

/**
 * Trigger for `trigger.shutdown`. Fires when the device is shutting down.
 *
 * Produces a typed [SystemState] item on the `state` data port.
 */
class ShutdownTrigger : Trigger<SystemState> {

    override val definition = systemStateDefinition(
        typeId = "trigger.shutdown",
        displayName = "Device Shutting Down",
        description = "Starts when the device is shutting down",
        category = NodeCategory.POWER_BATTERY,
    )

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<SystemState>> =
        systemStateFlow(
            source = TriggerSource.SYSTEM,
            triggerType = "shutdown",
            node = node,
            host = host,
        )
}
