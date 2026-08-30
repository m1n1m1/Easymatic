package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.items.SystemState
import io.github.m1n1m1.easymatic.engine.NodeOutput
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
