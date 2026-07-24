package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.SystemState
import com.example.ottomatic.engine.NodeOutput
import kotlinx.coroutines.flow.Flow

/**
 * Trigger for `trigger.wifi_state`. Fires when Wi-Fi is enabled or disabled.
 *
 * Produces a typed [SystemState] item on the `state` data port.
 */
class WifiStateTrigger : Trigger<SystemState> {

    override val definition = systemStateDefinition(
        typeId = "trigger.wifi_state",
        displayName = "Wi-Fi State Change",
        description = "Starts when Wi-Fi is enabled or disabled",
        category = NodeCategory.CONNECTIVITY,
        iconKey = "wifi",
        eventFilterLabel = "Event",
        eventFilterOptions = listOf("enabled", "disabled"),
    )

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<SystemState>> =
        systemStateFlow(
            source = TriggerSource.CONNECTIVITY,
            triggerType = "wifi_state",
            node = node,
            host = host,
        )
}
