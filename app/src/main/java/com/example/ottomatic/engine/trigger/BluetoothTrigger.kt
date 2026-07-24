package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.SystemState
import com.example.ottomatic.engine.NodeOutput
import kotlinx.coroutines.flow.Flow

/**
 * Trigger for `trigger.bluetooth`. Fires when Bluetooth is turned on or off.
 *
 * Produces a typed [SystemState] item on the `state` data port.
 */
class BluetoothTrigger : Trigger<SystemState> {

    override val definition = systemStateDefinition(
        typeId = "trigger.bluetooth",
        displayName = "Bluetooth State Change",
        description = "Starts when Bluetooth is turned on or off",
        category = NodeCategory.CONNECTIVITY,
        iconKey = "bluetooth",
        eventFilterLabel = "Event",
        eventFilterOptions = listOf("on", "off"),
    )

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<SystemState>> =
        systemStateFlow(
            source = TriggerSource.CONNECTIVITY,
            triggerType = "bluetooth",
            node = node,
            host = host,
        )
}
