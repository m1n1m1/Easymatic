package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.NoConfig
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.pulseTriggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Trigger for `trigger.macro_finished`. Fires when a macro (workflow) finishes
 * executing after being triggered. Subscribes to the engine-internal
 * [MacroEventBus] via [TriggerHost.macroLifecycleEvents].
 *
 * Produces no typed data output — only the EXECUTION pulse.
 */
class MacroFinishedTrigger : Trigger<NoConfig, Unit> {

    override val definition = pulseTriggerNode<NoConfig>(
        typeId = "trigger.macro_finished",
        displayName = "Macro Finished",
        description = "Fires when a macro finishes executing after being triggered",
        category = NodeCategory.AUTOMATION,
        icon = NodeIcon.BOLT,
    )

    override fun activate(config: NoConfig, node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<Unit>> =
        host.macroLifecycleEvents()
            .filter { it.source == TriggerSource.MACRO }
            .filter { it.payload[KEY_EVENT] == EVENT_FINISHED }
            .map { NodeOutput(Unit) }

    companion object {
        const val EVENT_FINISHED = "finished"
    }
}
