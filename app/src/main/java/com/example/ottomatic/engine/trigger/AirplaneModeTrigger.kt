package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.SystemState
import com.example.ottomatic.engine.NodeOutput
import kotlinx.coroutines.flow.Flow

/**
 * Trigger for `trigger.airplane_mode`. Fires when airplane mode is toggled.
 *
 * Produces a typed [SystemState] item on the `state` data port.
 */
class AirplaneModeTrigger : Trigger<SystemState> {

    override val definition = systemStateDefinition(
        typeId = "trigger.airplane_mode",
        displayName = "Airplane Mode Changed",
        description = "Starts when airplane mode is toggled",
        category = NodeCategory.CONNECTIVITY,
        eventFilterLabel = "Event",
        eventFilterOptions = listOf("on", "off"),
    )

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<SystemState>> =
        systemStateFlow(
            source = TriggerSource.CONNECTIVITY,
            triggerType = "airplane_mode",
            node = node,
            host = host,
        )
}
