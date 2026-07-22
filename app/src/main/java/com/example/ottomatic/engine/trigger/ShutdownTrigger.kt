package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.WorkflowNode
import kotlinx.coroutines.flow.Flow

/**
 * Trigger for `trigger.shutdown`. Fires when the device is shutting down.
 *
 * Produces a typed [com.example.ottomatic.domain.model.items.SystemState] item
 * on the `state` data port.
 */
class ShutdownTrigger : Trigger {

    override val typeId: String = TYPE_ID

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<TriggerEvent> =
        systemStateFlow(
            source = TriggerSource.SYSTEM,
            triggerType = "shutdown",
            node = node,
            host = host,
        )

    companion object {
        const val TYPE_ID = "trigger.shutdown"
    }
}
