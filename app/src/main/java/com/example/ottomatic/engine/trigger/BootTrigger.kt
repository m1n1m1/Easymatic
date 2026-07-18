package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.WorkflowNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Trigger for `trigger.boot`. Fires once after the device finishes booting.
 * The [BootReceiver] pushes a sentinel event onto the bus; this trigger maps
 * it to the concrete node id.
 */
class BootTrigger : Trigger {

    override val typeId: String = TYPE_ID

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<TriggerEvent> =
        host.busEvents()
            .filter { it.source == TriggerSource.BOOT }
            .map { TriggerEvent(triggerNodeId = node.id) }

    companion object {
        const val TYPE_ID = "trigger.boot"
    }
}
