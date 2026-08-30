package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.items.SystemState
import io.github.m1n1m1.easymatic.engine.NodeOutput
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
