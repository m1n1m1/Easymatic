package com.example.ottomatic.engine.trigger

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Trigger for `trigger.manual`. Fires when the user taps "Run" in the editor.
 *
 * Holds a [MutableSharedFlow] per activation so the ViewModel can emit into it.
 * The companion keeps a registry of active flows keyed by node id so the
 * UI can reach the correct trigger instance.
 */
class ManualTrigger : Trigger<Unit> {

    override val definition = triggerNode<Unit>(
        typeId = TYPE_ID,
        displayName = "Manual Trigger",
        description = "Starts the workflow when you tap run",
        category = NodeCategory.MANUAL,
        iconKey = "bolt",
        encodeData = { emptyMap() },
    )

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<Unit>> {
        val flow = MutableSharedFlow<NodeOutput<Unit>>(extraBufferCapacity = 1)
        activeFlows[node.id] = flow
        return flow
    }

    companion object {
        const val TYPE_ID = "trigger.manual"

        private val activeFlows = mutableMapOf<String, MutableSharedFlow<NodeOutput<Unit>>>()

        /** Called by the ViewModel to fire a manual trigger for the given node. */
        fun fire(nodeId: String) {
            activeFlows[nodeId]?.tryEmit(NodeOutput(Unit))
        }

        /** Removes the flow when the workflow run is cancelled. */
        fun release(nodeId: String) {
            activeFlows.remove(nodeId)
        }
    }
}
