package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.WorkflowNode
import kotlinx.coroutines.flow.Flow

/**
 * Trigger for `trigger.call_state`. Fires when the phone call state changes
 * (ringing, offhook, idle).
 *
 * Produces a typed [com.example.ottomatic.domain.model.items.SystemState] item
 * on the `state` data port.
 */
class CallStateTrigger : Trigger {

    override val typeId: String = TYPE_ID

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<TriggerEvent> =
        systemStateFlow(
            source = TriggerSource.CONNECTIVITY,
            triggerType = "call_state",
            node = node,
            host = host,
        )

    companion object {
        const val TYPE_ID = "trigger.call_state"
    }
}
