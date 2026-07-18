package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.WorkflowNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Trigger for `trigger.sms`. Listens to the bus for SMS events (which arrive
 * with the sentinel node id `*`) and fans them out to every sms trigger node,
 * optionally filtering by sender.
 */
class SmsTrigger : Trigger {

    override val typeId: String = TYPE_ID

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<TriggerEvent> =
        host.busEvents()
            .filter { it.source == TriggerSource.SMS }
            .filter { event ->
                val senderFilter = node.config["sender"]?.takeIf { it.isNotBlank() }
                senderFilter == null || event.payload["sender"] == senderFilter
            }
            .map { event ->
                TriggerEvent(
                    triggerNodeId = node.id,
                    payload = event.payload,
                )
            }

    companion object {
        const val TYPE_ID = "trigger.sms"
    }
}
