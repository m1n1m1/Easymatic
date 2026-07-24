package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.SystemState
import com.example.ottomatic.engine.NodeOutput
import kotlinx.coroutines.flow.Flow

/**
 * Trigger for `trigger.headset`. Fires when a wired headset is plugged in or
 * unplugged.
 *
 * Produces a typed [SystemState] item on the `state` data port.
 */
class HeadsetTrigger : Trigger<SystemState> {

    override val definition = systemStateDefinition(
        typeId = "trigger.headset",
        displayName = "Headset Plugged",
        description = "Starts when a wired headset is plugged or unplugged",
        category = NodeCategory.CONNECTIVITY,
        eventFilterLabel = "Event",
        eventFilterOptions = listOf("plugged", "unplugged"),
    )

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<SystemState>> =
        systemStateFlow(
            source = TriggerSource.HARDWARE,
            triggerType = "headset",
            node = node,
            host = host,
        )
}
