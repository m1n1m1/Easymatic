package com.example.ottomatic.engine.trigger

import com.example.ottomatic.domain.model.WorkflowNode
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
class AppInitTrigger : Trigger {

    override val typeId: String = TYPE_ID

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<TriggerEvent> =
        flowOf(TriggerEvent(triggerNodeId = node.id))

    companion object {
        const val TYPE_ID = "trigger.app_init"
    }
}
