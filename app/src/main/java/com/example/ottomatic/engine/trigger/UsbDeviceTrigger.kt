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
class UsbDeviceTrigger : Trigger<SystemState> {

    override val definition = systemStateDefinition(
        typeId = "trigger.usb_device",
        displayName = "USB Device Connected",
        description = "Starts when a USB device is connected or disconnected",
        category = NodeCategory.CONNECTIVITY,
        eventFilterLabel = "Event",
        eventFilterOptions = listOf("connected", "disconnected"),
    )

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<SystemState>> =
        systemStateFlow(
            source = TriggerSource.HARDWARE,
            triggerType = "usb_device",
            node = node,
            host = host,
        )
}
