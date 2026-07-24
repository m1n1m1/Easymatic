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
 * Trigger for `trigger.macro_enabled`. Fires when a macro (workflow) is
 * enabled — i.e. when its [com.example.ottomatic.engine.WorkflowRunner.run]
 * starts and arms its triggers. Subscribes to the engine-internal
 * [MacroEventBus] via [TriggerHost.macroLifecycleEvents].
 *
 * Produces no typed data output — only the EXECUTION pulse.
 */
class MacroEnabledTrigger : Trigger<NoConfig, Unit> {

    override val definition = pulseTriggerNode<NoConfig>(
        typeId = "trigger.macro_enabled",
        displayName = "Macro Enabled",
        description = "Fires when this macro is enabled (its triggers are armed)",
        category = NodeCategory.AUTOMATION,
        icon = NodeIcon.BOLT,
    )

    override fun activate(config: NoConfig, node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<Unit>> =
        host.macroLifecycleEvents()
            .filter { it.source == TriggerSource.MACRO }
            .filter { it.payload[KEY_EVENT] == EVENT_ENABLED }
            .map { NodeOutput(Unit) }

    companion object {
        const val EVENT_ENABLED = "enabled"
    }
}
