package com.example.ottomatic.engine.trigger

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Trigger for `trigger.empty`. Fires exactly once immediately when the
 * workflow runner activates it — useful as a no-op entry point or for macro
 * composition where the real logic is driven by constraints or downstream
 * nodes.
 */
class EmptyTrigger : Trigger<Unit> {

    override val definition = triggerNode<Unit>(
        typeId = "trigger.empty",
        displayName = "Empty Trigger",
        description = "Fires immediately when the workflow starts (no external event)",
        category = NodeCategory.AUTOMATION,
        iconKey = "bolt",
        encodeData = { emptyMap() },
    )

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<Unit>> =
        flowOf(NodeOutput(Unit))
}
