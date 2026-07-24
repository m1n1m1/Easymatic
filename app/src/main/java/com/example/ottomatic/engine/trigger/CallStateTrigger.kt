package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.SystemState
import com.example.ottomatic.engine.NodeOutput
import kotlinx.coroutines.flow.Flow

/**
 * Trigger for `trigger.call_state`. Fires when the phone call state changes
 * (ringing, offhook, idle).
 *
 * Produces a typed [SystemState] item on the `state` data port.
 */
class CallStateTrigger : Trigger<SystemState> {

    override val definition = systemStateDefinition(
        typeId = "trigger.call_state",
        displayName = "Incoming Call State",
        description = "Starts when the phone call state changes (ringing, offhook, idle)",
        category = NodeCategory.PHONE_MEDIA,
        eventFilterLabel = "State",
        eventFilterOptions = listOf("ringing", "offhook", "idle"),
    )

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<SystemState>> =
        systemStateFlow(
            source = TriggerSource.CONNECTIVITY,
            triggerType = "call_state",
            node = node,
            host = host,
        )
}
