package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.SmsMessage
import com.example.ottomatic.domain.model.schema.Item
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Trigger for `trigger.sms`. Listens to the bus for SMS events (which arrive
 * with the sentinel node id `*`) and fans them out to every sms trigger node,
 * optionally filtering by sender.
 *
 * Produces a typed [SmsMessage] item on the `sms` data port.
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
                val message = SmsMessage(
                    sender = event.payload["sender"].orEmpty(),
                    body = event.payload["body"].orEmpty(),
                    timestamp = event.payload["timestamp"]?.toLongOrNull() ?: event.firedAtEpochMs,
                )
                TriggerEvent(
                    triggerNodeId = node.id,
                    dataOut = mapOf("sms" to Item.of(message)),
                )
            }

    companion object {
        const val TYPE_ID = "trigger.sms"
    }
}
