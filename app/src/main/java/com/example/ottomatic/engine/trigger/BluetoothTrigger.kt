package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.SystemState
import com.example.ottomatic.engine.NodeOutput
import kotlinx.coroutines.flow.Flow

/**
 * Trigger for `trigger.bluetooth`. Fires when the Bluetooth radio is turned on
 * or off.
 *
 * Produces a typed [SystemState] item on the `state` data port.
 */
class BluetoothTrigger : Trigger<EventFilter<OnOffEvent>, SystemState> {

    override val definition = systemStateDefinition<EventFilter<OnOffEvent>>(
        typeId = "trigger.bluetooth",
        displayName = "Bluetooth State Change",
        description = "Starts when Bluetooth is turned on or off",
        category = NodeCategory.CONNECTIVITY,
        icon = NodeIcon.BLUETOOTH,
    )

    override fun activate(
        config: EventFilter<OnOffEvent>,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<SystemState>> = systemStateFlow(
        source = TriggerSource.CONNECTIVITY,
        triggerType = "bluetooth",
        host = host,
        event = config.event,
    )
}
