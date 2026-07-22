package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.WorkflowNode
import kotlinx.coroutines.flow.Flow

/**
 * Trigger for `trigger.bluetooth_connect`. Fires when a Bluetooth device
 * connects or disconnects. The device name is carried in the `detail` field.
 *
 * Produces a typed [com.example.ottomatic.domain.model.items.SystemState] item
 * on the `state` data port.
 */
class BluetoothConnectTrigger : Trigger {

    override val typeId: String = TYPE_ID

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<TriggerEvent> =
        systemStateFlow(
            source = TriggerSource.CONNECTIVITY,
            triggerType = "bluetooth_connect",
            node = node,
            host = host,
        )

    companion object {
        const val TYPE_ID = "trigger.bluetooth_connect"
    }
}
