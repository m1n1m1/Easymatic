package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.SystemState
import com.example.ottomatic.engine.NodeOutput
import kotlinx.coroutines.flow.Flow

/**
 * Trigger for `trigger.dock`. Fires when the device is docked or undocked.
 * Dock type (car / desk) is carried in the `detail` field.
 *
 * Produces a typed [SystemState] item on the `state` data port.
 */
class DockTrigger : Trigger<SystemState> {

    override val definition = systemStateDefinition(
        typeId = "trigger.dock",
        displayName = "Device Docked",
        description = "Starts when the device is docked or undocked",
        category = NodeCategory.CONNECTIVITY,
        eventFilterLabel = "Event",
        eventFilterOptions = listOf("docked", "undocked"),
    )

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<SystemState>> =
        systemStateFlow(
            source = TriggerSource.HARDWARE,
            triggerType = "dock",
            node = node,
            host = host,
        )
}
