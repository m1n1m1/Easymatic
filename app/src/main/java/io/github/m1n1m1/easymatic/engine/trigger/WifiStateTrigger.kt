package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.items.SystemState
import io.github.m1n1m1.easymatic.engine.NodeOutput
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
