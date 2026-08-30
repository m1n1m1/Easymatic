package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.config.NoConfig
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.pulseTriggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Trigger for `trigger.empty`. Fires exactly once immediately when the
 * workflow runner activates it — useful as a no-op entry point or for macro
 * composition where the real logic is driven by constraints or downstream
 * nodes.
 */
class EmptyTrigger : Trigger<NoConfig, Unit> {

    override val definition = pulseTriggerNode<NoConfig>(
        typeId = "trigger.empty",
        displayName = "Empty Trigger",
        description = "Fires immediately when the workflow starts (no external event)",
        category = NodeCategory.AUTOMATION,
        icon = NodeIcon.BOLT,
    )

    override fun activate(config: NoConfig, node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<Unit>> =
        flowOf(NodeOutput(Unit))
}
