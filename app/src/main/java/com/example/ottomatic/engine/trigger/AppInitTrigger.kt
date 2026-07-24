package com.example.ottomatic.engine.trigger

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.NoConfig
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.pulseTriggerNode
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
class AppInitTrigger : Trigger<NoConfig, Unit> {

    override val definition = pulseTriggerNode<NoConfig>(
        typeId = "trigger.app_init",
        displayName = "App Initialised",
        description = "Fires when the app is initialised and running",
        category = NodeCategory.AUTOMATION,
        icon = NodeIcon.BOLT,
    )

    override fun activate(config: NoConfig, node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<Unit>> =
        flowOf(NodeOutput(Unit))
}
