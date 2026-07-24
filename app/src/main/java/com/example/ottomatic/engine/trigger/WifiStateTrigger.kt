package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.SystemState
import com.example.ottomatic.engine.NodeOutput
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** The Wi-Fi transitions `trigger.wifi_state` can filter on. */
@Serializable
enum class WifiStateEvent {
    ENABLED,
    DISABLED,
}

/**
 * Trigger for `trigger.wifi_state`. Fires when Wi-Fi is enabled or disabled.
 *
 * Produces a typed [SystemState] item on the `state` data port.
 */
class WifiStateTrigger : Trigger<EventFilter<WifiStateEvent>, SystemState> {

    override val definition = systemStateDefinition<EventFilter<WifiStateEvent>>(
        typeId = "trigger.wifi_state",
        displayName = "Wi-Fi State Change",
        description = "Starts when Wi-Fi is enabled or disabled",
        category = NodeCategory.CONNECTIVITY,
        icon = NodeIcon.WIFI,
    )

    override fun activate(
        config: EventFilter<WifiStateEvent>,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<SystemState>> = systemStateFlow(
        source = TriggerSource.CONNECTIVITY,
        triggerType = "wifi_state",
        host = host,
        event = config.event,
    )
}
