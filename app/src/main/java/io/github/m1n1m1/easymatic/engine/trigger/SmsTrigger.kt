package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.permissions.PermissionRequirement
import io.github.m1n1m1.easymatic.core.permissions.Permissions
import io.github.m1n1m1.easymatic.core.permissions.PrerequisiteType
import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.PhoneRef
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.config.Hint
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.PhoneNumber
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.SmsMessage
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.triggerNode
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
    @Label("Sender")
    @Hint("optional — any sender when empty")
    @PhoneNumber val sender: String = "",
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
        // Node-addressed, so a text that woke the process is drained rather than
        // discarded — see `BootTrigger` for the same reasoning.
        return host.busEventsFor(node.id)
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
