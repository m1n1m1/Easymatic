package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.SystemState
import com.example.ottomatic.engine.NodeOutput
import kotlinx.coroutines.flow.Flow

/**
 * Trigger for `trigger.usb_device`. Fires when a USB device is connected or
 * disconnected.
 *
 * Produces a typed [SystemState] item on the `state` data port.
 */
class UsbDeviceTrigger : Trigger<EventFilter<ConnectionEvent>, SystemState> {

    override val definition = systemStateDefinition<EventFilter<ConnectionEvent>>(
        typeId = "trigger.usb_device",
        displayName = "USB Device Connected",
        description = "Starts when a USB device is connected or disconnected",
        category = NodeCategory.CONNECTIVITY,
    )

    override fun activate(
        config: EventFilter<ConnectionEvent>,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<SystemState>> = systemStateFlow(
        source = TriggerSource.HARDWARE,
        triggerType = "usb_device",
        host = host,
        event = config.event,
    )
}
