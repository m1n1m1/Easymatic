package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.config.Hint
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Picker
import io.github.m1n1m1.easymatic.domain.model.config.PickerKind
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.items.CallEvent
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/**
 * The phone call states `trigger.call_state` can filter on.
 *
 * Named for what a person sees rather than for what the radio calls it: the telephony
 * broadcast says `OFFHOOK` and `IDLE`, which are terms from a handset that had a hook,
 * and neither means anything to somebody wiring up a macro. The entry names are also the
 * payload values, lower-cased, so the mapping stays mechanical.
 */
@Serializable
enum class CallStateEvent {
    RINGING,
    ACTIVE,
    ENDED,
}

/** Config for `trigger.call_state`; both filters are optional. */
@Serializable
data class CallStateConfig(
    @Label("Event") val event: CallStateEvent? = null,
    @Label("From app")
    @Hint("optional")
    @Picker(PickerKind.APP_FILTER) val packageFilter: String = "",
)

/**
 * Trigger for `trigger.call_state`. Fires whenever a call starts ringing, is picked up,
 * or finishes — **on the cellular radio and in any app that places calls**.
 *
 * The generic third-party half is the reason this node stopped being a Tier 1 broadcast
 * trigger emitting a `SystemState`. Teams, WhatsApp, Discord and Signal never touch
 * telephony, so no broadcast describes their calls at all; what does describe them is the
 * notification each of them posts, which since API 31 has a shape the platform defined
 * for exactly this. `CallSessions` merges that stream with the telephony one before
 * anything reaches the bus, so this node reads one kind of event and never has to know
 * which road it came down. There is no list of calling apps anywhere, and there
 * deliberately never will be — the same position `trigger.message` takes, for the same
 * reason.
 *
 * That merge is also what fills in **who is calling on an ordinary phone call**. The
 * telephony broadcast will not say below `READ_CALL_LOG`, which this app does not ask
 * for; the dialer's own notification says it to anyone with notification access.
 *
 * `trigger.call_ended` sits beside this one and overlaps it on purpose, exactly as
 * `trigger.message` overlaps `trigger.notification`: this fires on all three states, that
 * one fires on the last and carries what only exists once a call is over.
 */
class CallStateTrigger : Trigger<CallStateConfig, CallEvent> {

    override val definition = triggerNode<CallStateConfig, CallEvent>(
        typeId = "trigger.call_state",
        displayName = "Call State",
        description = "Starts when a call starts ringing, is answered or ends, " +
            "on the phone or in Teams, WhatsApp, Discord or another calling app",
        category = NodeCategory.PHONE_MEDIA,
        icon = NodeIcon.CALL,
        output = dataOut<CallEvent>("call", label = "Call"),
        permissions = CALL_PERMISSIONS,
    )

    override fun activate(
        config: CallStateConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<CallEvent>> {
        val packageFilter = config.packageFilter.takeIf { it.isNotBlank() }
        // Node-addressed, so a call that woke the process is drained rather than
        // discarded — `trigger.message` and `trigger.sms` do the same.
        return host.busEventsFor(node.id)
            .filter { it.source == TriggerSource.CALL }
            .map { it.toCallEvent() }
            .filter { call -> config.event == null || config.event.payloadValue == call.state }
            .filter { call -> packageFilter == null || call.packageName == packageFilter }
            .map { NodeOutput(it) }
    }
}
