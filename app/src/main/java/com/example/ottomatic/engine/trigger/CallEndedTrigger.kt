package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.CallPayload
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.Hint
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Picker
import com.example.ottomatic.domain.model.config.PickerKind
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.CallEvent
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/** Whether a finished call was picked up. */
@Serializable
enum class CallOutcome {
    ANSWERED,
    MISSED,
}

/** Which way a finished call was going. */
@Serializable
enum class CallDirection {
    INCOMING,
    OUTGOING,
}

/** Config for `trigger.call_ended`; every filter is optional. */
@Serializable
data class CallEndedConfig(
    @Label("Outcome") val outcome: CallOutcome? = null,
    @Label("Direction") val direction: CallDirection? = null,
    @Label("From app")
    @Hint("optional")
    @Picker(PickerKind.APP_FILTER) val packageFilter: String = "",
)

/**
 * Trigger for `trigger.call_ended`. Fires when a call finishes, carrying how long it
 * lasted and whether anybody picked it up.
 *
 * A refinement of `trigger.call_state` rather than a separate mechanism, and it earns its
 * own node on `trigger.message`'s argument: it fires on a far smaller set, it carries
 * more, and it is the one people actually reach for. "Text them back if I miss a call" is
 * this node with one filter set; on `trigger.call_state` it is an `action.if` over two
 * fields of a struct, downstream of a trigger that also fired twice already.
 *
 * **A missed call is a call that ended having never been answered**, which is why this is
 * one node with an outcome filter rather than two nodes. Both halves come from the same
 * session and the same moment, and splitting them would have put the duration on one node
 * and the missed-call case on another that could never carry one.
 *
 * Works for calls in any app, for the reason and by the machinery `trigger.call_state`
 * describes. For an app call the end is its notification being taken down, which is the
 * only signal Android gives — that is the call ending in every ordinary case, and it is
 * worth knowing that it is not quite the same statement.
 */
class CallEndedTrigger : Trigger<CallEndedConfig, CallEvent> {

    override val definition = triggerNode<CallEndedConfig, CallEvent>(
        typeId = "trigger.call_ended",
        displayName = "Call Ended",
        description = "Starts when a call finishes, with how long it lasted and whether it " +
            "was answered — on the phone or in Teams, WhatsApp, Discord or another calling app",
        category = NodeCategory.PHONE_MEDIA,
        icon = NodeIcon.CALL,
        output = dataOut<CallEvent>("call", label = "Call"),
        permissions = CALL_PERMISSIONS,
    )

    override fun activate(
        config: CallEndedConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<CallEvent>> {
        val packageFilter = config.packageFilter.takeIf { it.isNotBlank() }
        return host.busEventsFor(node.id)
            .filter { it.source == TriggerSource.CALL }
            .map { it.toCallEvent() }
            .filter { it.state == CallPayload.STATE_ENDED }
            .filter { call -> config.outcome == null || config.outcome.matches(call) }
            .filter { call -> config.direction == null || config.direction.matches(call) }
            .filter { call -> packageFilter == null || call.packageName == packageFilter }
            .map { NodeOutput(it) }
    }

    private fun CallOutcome.matches(call: CallEvent): Boolean = when (this) {
        CallOutcome.ANSWERED -> call.answered
        CallOutcome.MISSED -> !call.answered
    }

    private fun CallDirection.matches(call: CallEvent): Boolean = when (this) {
        CallDirection.INCOMING -> call.incoming
        CallDirection.OUTGOING -> !call.incoming
    }
}
