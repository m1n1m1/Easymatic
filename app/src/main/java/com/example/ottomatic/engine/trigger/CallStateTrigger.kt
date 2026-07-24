package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.SystemState
import com.example.ottomatic.engine.NodeOutput
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** The phone call states `trigger.call_state` can filter on. */
@Serializable
enum class CallStateEvent {
    RINGING,
    OFFHOOK,
    IDLE,
}

/**
 * Trigger for `trigger.call_state`. Fires when the phone call state changes
 * (ringing, offhook, idle).
 *
 * Produces a typed [SystemState] item on the `state` data port.
 */
class CallStateTrigger : Trigger<EventFilter<CallStateEvent>, SystemState> {

    override val definition = systemStateDefinition<EventFilter<CallStateEvent>>(
        typeId = "trigger.call_state",
        displayName = "Incoming Call State",
        description = "Starts when the phone call state changes (ringing, offhook, idle)",
        category = NodeCategory.PHONE_MEDIA,
    )

    override fun activate(
        config: EventFilter<CallStateEvent>,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<SystemState>> = systemStateFlow(
        source = TriggerSource.CONNECTIVITY,
        triggerType = "call_state",
        host = host,
        event = config.event,
    )
}
