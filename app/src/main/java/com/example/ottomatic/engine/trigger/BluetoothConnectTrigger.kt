package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.SystemState
import com.example.ottomatic.engine.NodeOutput
import kotlinx.coroutines.flow.Flow

/**
 * Trigger for `trigger.bluetooth_connect`. Fires when a Bluetooth device
 * connects or disconnects.
 *
 * Produces a typed [SystemState] item on the `state` data port; the device name
 * arrives in [SystemState.detail].
 */
class BluetoothConnectTrigger : Trigger<EventFilter<ConnectionEvent>, SystemState> {

    override val definition = systemStateDefinition<EventFilter<ConnectionEvent>>(
        typeId = "trigger.bluetooth_connect",
        displayName = "Bluetooth Device Connected",
        description = "Starts when a Bluetooth device connects or disconnects",
        category = NodeCategory.CONNECTIVITY,
        icon = NodeIcon.BLUETOOTH,
    )

    override fun activate(
        config: EventFilter<ConnectionEvent>,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<SystemState>> = systemStateFlow(
        source = TriggerSource.CONNECTIVITY,
        triggerType = "bluetooth_connect",
        host = host,
        event = config.event,
    )
}
