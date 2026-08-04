package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.permissions.PermissionRequirement
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.PhoneRef
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.PhoneNumber
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.SmsMessage
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/**
 * Config for `trigger.sms`; an empty filter matches every sender.
 *
 * `sender` holds a [PhoneRef] spec — a number typed in, or a contact chosen from
 * the address book. No `@Wired`: a trigger exposes no data inputs.
 */
@Serializable
data class SmsTriggerConfig(
    @Label("Sender (optional — any sender when empty)") @PhoneNumber val sender: String = "",
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
        // The manifest has held this since the receiver was written, but the node
        // never declared it — so somebody who denied it got a macro that looked
        // armed and simply never fired, with nothing anywhere saying so.
        permissions = listOf(
            PermissionRequirement(
                manifestPermission = Permissions.RECEIVE_SMS.manifest,
                type = PrerequisiteType.RUNTIME,
                rationaleKey = "sms.receive",
            ),
        ),
    )

    override fun activate(
        config: SmsTriggerConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<SmsMessage>> {
        val senderFilter = PhoneRef.parse(config.sender)
        return host.busEvents()
            .filter { it.source == TriggerSource.SMS }
            .filter { event ->
                senderFilter == null || host.senderMatches(senderFilter, event.payload[KEY_SENDER].orEmpty())
            }
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

    /**
     * Whether [sender] is who the filter meant.
     *
     * Resolved **per event, not per arm** — the opposite of `trigger.geofence`,
     * which resolves its place in [activate] because a geofence has to be
     * *registered* with the OS. A filter is only a predicate, an SMS is not a hot
     * path, and resolving each time means an edit in the Contacts app takes effect
     * immediately with no re-arm signal to invent.
     *
     * A contact that cannot be read matches nothing; see [TriggerHost.contactNumber]
     * for why that is the safe direction to fail in.
     */
    private fun TriggerHost.senderMatches(filter: PhoneRef, sender: String): Boolean = when (filter) {
        is PhoneRef.Literal -> PhoneRef.matchesNumber(filter.number, sender)
        is PhoneRef.Contact -> contactNumber(filter.lookupKey)?.let { PhoneRef.matchesNumber(it, sender) } ?: false
    }

    companion object {
        const val KEY_SENDER = "sender"
        const val KEY_BODY = "body"
    }
}
