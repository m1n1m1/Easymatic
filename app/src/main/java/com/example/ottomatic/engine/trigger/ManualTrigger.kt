package com.example.ottomatic.engine.trigger

import com.example.ottomatic.domain.model.WorkflowNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Trigger for `trigger.manual`. Fires when the user taps "Run" in the editor.
 *
 * Holds a [MutableSharedFlow] per activation so the ViewModel can emit into it.
 * The companion keeps a registry of active flows keyed by node id so the
 * UI can reach the correct trigger instance.
 */
class ManualTrigger : Trigger {

    override val typeId: String = TYPE_ID

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<TriggerEvent> {
        val flow = MutableSharedFlow<TriggerEvent>(extraBufferCapacity = 1)
        activeFlows[node.id] = flow
        return flow
    }

    companion object {
        const val TYPE_ID = "trigger.manual"

        private val activeFlows = mutableMapOf<String, MutableSharedFlow<TriggerEvent>>()

        /** Called by the ViewModel to fire a manual trigger for the given node. */
        fun fire(nodeId: String) {
            val event = TriggerEvent(triggerNodeId = nodeId)
            activeFlows[nodeId]?.tryEmit(event)
        }

        /** Removes the flow when the workflow run is cancelled. */
        fun release(nodeId: String) {
            activeFlows.remove(nodeId)
        }
    }
}
