package com.example.ottomatic.engine.trigger

import com.example.ottomatic.domain.model.WorkflowNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Trigger for `trigger.empty`. Fires exactly once immediately when the
 * workflow runner activates it — useful as a no-op entry point or for macro
 * composition where the real logic is driven by constraints or downstream
 * nodes.
 */
class EmptyTrigger : Trigger {

    override val typeId: String = TYPE_ID

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<TriggerEvent> =
        flowOf(TriggerEvent(triggerNodeId = node.id))

    companion object {
        const val TYPE_ID = "trigger.empty"
    }
}
