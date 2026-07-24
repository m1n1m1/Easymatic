package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.SystemState
import com.example.ottomatic.engine.NodeOutput
import kotlinx.coroutines.flow.Flow

/**
 * Trigger for `trigger.bluetooth_connect`. Fires when a Bluetooth device
 * connects or disconnects. The device name is carried in the `detail` field.
 *
 * Produces a typed [SystemState] item on the `state` data port.
 */
class BluetoothConnectTrigger : Trigger<SystemState> {

    override val definition = systemStateDefinition(
        typeId = "trigger.bluetooth_connect",
        displayName = "Bluetooth Device Connected",
        description = "Starts when a Bluetooth device connects or disconnects",
        category = NodeCategory.CONNECTIVITY,
        iconKey = "bluetooth",
        eventFilterLabel = "Event",
        eventFilterOptions = listOf("connected", "disconnected"),
    )

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<SystemState>> =
        systemStateFlow(
            source = TriggerSource.CONNECTIVITY,
            triggerType = "bluetooth_connect",
            node = node,
            host = host,
        )
}
