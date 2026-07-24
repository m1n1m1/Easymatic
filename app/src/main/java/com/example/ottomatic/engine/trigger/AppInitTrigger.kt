package com.example.ottomatic.engine.trigger

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Trigger for `trigger.app_init`. Fires once when the workflow runner
 * activates it, signalling that the app is initialised and running.
 *
 * In the current on-demand execution model (workflows run from the editor
 * after the app is already up), this fires at workflow start. When a
 * persistent background daemon is introduced in a later tier, this trigger
 * will instead subscribe to the real app-init lifecycle event.
 */
class AppInitTrigger : Trigger<Unit> {

    override val definition = triggerNode<Unit>(
        typeId = "trigger.app_init",
        displayName = "App Initialised",
        description = "Fires when the app is initialised and running",
        category = NodeCategory.AUTOMATION,
        iconKey = "bolt",
        encodeData = { emptyMap() },
    )

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<Unit>> =
        flowOf(NodeOutput(Unit))
}
