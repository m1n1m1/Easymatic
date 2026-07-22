package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.WorkflowNode
import kotlinx.coroutines.flow.Flow

/**
 * Trigger for `trigger.wifi_state`. Fires when Wi-Fi is enabled or disabled.
 *
 * Produces a typed [com.example.ottomatic.domain.model.items.SystemState] item
 * on the `state` data port.
 */
class WifiStateTrigger : Trigger {

    override val typeId: String = TYPE_ID

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<TriggerEvent> =
        systemStateFlow(
            source = TriggerSource.CONNECTIVITY,
            triggerType = "wifi_state",
            node = node,
            host = host,
        )

    companion object {
        const val TYPE_ID = "trigger.wifi_state"
    }
}
