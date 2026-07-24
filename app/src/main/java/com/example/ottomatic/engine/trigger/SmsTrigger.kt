package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.SmsMessage
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.triggerNode
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
class SmsTrigger : Trigger<SmsMessage> {

    override val definition = triggerNode<SmsMessage>(
        typeId = "trigger.sms",
        displayName = "SMS Received",
        description = "Starts when an SMS arrives",
        category = NodeCategory.MESSAGING,
        iconKey = "sms",
        dataOutputs = listOf(dataOut<SmsMessage>("sms")),
        configFields = listOf(
            ConfigField(
                key = "sender",
                label = "Sender filter (phone number, optional)",
                type = ConfigFieldType.STR,
            ),
        ),
        encodeData = { message -> mapOf("sms" to Item.of(message)) },
    )

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<SmsMessage>> =
        host.busEvents()
            .filter { it.source == TriggerSource.SMS }
            .filter { event ->
                val senderFilter = node.config["sender"]?.takeIf { it.isNotBlank() }
                senderFilter == null || event.payload["sender"] == senderFilter
            }
            .map { event ->
                NodeOutput(
                    SmsMessage(
                        sender = event.payload["sender"].orEmpty(),
                        body = event.payload["body"].orEmpty(),
                        timestamp = event.payload["timestamp"]?.toLongOrNull() ?: event.firedAtEpochMs,
                    ),
                )
            }
}
