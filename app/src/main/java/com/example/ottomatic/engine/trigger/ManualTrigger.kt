package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.NoConfig
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.pulseTriggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Trigger for `trigger.manual`. Fires when the user taps "Run" in the editor.
 *
 * Holds a [MutableSharedFlow] per activation so the ViewModel can emit into it.
 * The companion keeps a registry of active flows keyed by node id so the
 * UI can reach the correct trigger instance.
 */
class ManualTrigger : Trigger<NoConfig, Unit> {

    override val definition = pulseTriggerNode<NoConfig>(
        typeId = TYPE_ID.value,
        displayName = "Manual Trigger",
        description = "Starts the workflow when you tap run",
        category = NodeCategory.MANUAL,
        icon = NodeIcon.BOLT,
    )

    override fun activate(config: NoConfig, node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<Unit>> {
        val flow = MutableSharedFlow<NodeOutput<Unit>>(extraBufferCapacity = 1)
        activeFlows[node.id] = flow
        return flow
    }

    companion object {
        val TYPE_ID = NodeTypeId("trigger.manual")

        private val activeFlows = mutableMapOf<NodeId, MutableSharedFlow<NodeOutput<Unit>>>()

        /** Called by the ViewModel to fire a manual trigger for the given node. */
        fun fire(nodeId: NodeId) {
            activeFlows[nodeId]?.tryEmit(NodeOutput(Unit))
        }

        /** Removes the flow when the workflow run is cancelled. */
        fun release(nodeId: NodeId) {
            activeFlows.remove(nodeId)
        }
    }
}
