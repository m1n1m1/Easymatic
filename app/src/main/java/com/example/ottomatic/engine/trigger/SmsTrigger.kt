package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.SmsMessage
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/** Config for `trigger.sms`; an empty filter matches every sender. */
@Serializable
data class SmsTriggerConfig(
    @Label("Sender filter (phone number, optional)") val sender: String = "",
)

/**
 * Trigger for `trigger.sms`. Listens to the bus for SMS events (which arrive
 * with the sentinel node id `*`) and fans them out to every sms trigger node,
 * optionally filtering by sender.
 *
 * Produces a typed [SmsMessage] item on the `sms` data port.
 */
class SmsTrigger : Trigger<SmsTriggerConfig, SmsMessage> {

    override val definition = triggerNode<SmsTriggerConfig, SmsMessage>(
        typeId = "trigger.sms",
        displayName = "SMS Received",
        description = "Starts when an SMS arrives",
        category = NodeCategory.MESSAGING,
        icon = NodeIcon.SMS,
        output = dataOut<SmsMessage>("sms", label = "SMS"),
    )

    override fun activate(
        config: SmsTriggerConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<SmsMessage>> {
        val senderFilter = config.sender.takeIf { it.isNotBlank() }
        return host.busEvents()
            .filter { it.source == TriggerSource.SMS }
            .filter { senderFilter == null || it.payload[KEY_SENDER] == senderFilter }
            .map { event ->
                NodeOutput(
                    SmsMessage(
                        sender = event.payload[KEY_SENDER].orEmpty(),
                        body = event.payload[KEY_BODY].orEmpty(),
                        timestamp = event.timestamp,
                    ),
                )
            }
    }

    companion object {
        const val KEY_SENDER = "sender"
        const val KEY_BODY = "body"
    }
}
