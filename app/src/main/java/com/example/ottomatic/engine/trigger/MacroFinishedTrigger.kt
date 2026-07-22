package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.WorkflowNode
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
class MacroFinishedTrigger : Trigger {

    override val typeId: String = TYPE_ID

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<TriggerEvent> =
        host.macroLifecycleEvents()
            .filter { it.source == TriggerSource.MACRO }
            .filter { it.payload[KEY_EVENT] == EVENT_FINISHED }
            .map { TriggerEvent(triggerNodeId = node.id) }

    companion object {
        const val TYPE_ID = "trigger.macro_finished"

        const val KEY_EVENT = "event"
        const val EVENT_FINISHED = "finished"
    }
}
