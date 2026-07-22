package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.WorkflowNode
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
class MacroEnabledTrigger : Trigger {

    override val typeId: String = TYPE_ID

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<TriggerEvent> =
        host.macroLifecycleEvents()
            .filter { it.source == TriggerSource.MACRO }
            .filter { it.payload[KEY_EVENT] == EVENT_ENABLED }
            .map { TriggerEvent(triggerNodeId = node.id) }

    companion object {
        const val TYPE_ID = "trigger.macro_enabled"

        const val KEY_EVENT = "event"
        const val EVENT_ENABLED = "enabled"
    }
}
