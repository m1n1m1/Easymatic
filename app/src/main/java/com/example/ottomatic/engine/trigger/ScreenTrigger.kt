package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.SystemState
import com.example.ottomatic.engine.NodeOutput
import kotlinx.coroutines.flow.Flow

/**
 * Trigger for `trigger.screen`. Fires when the screen turns on or off.
 *
 * Produced by the runtime-registered [com.example.ottomatic.data.trigger.ScreenBroadcastBridge].
 *
 * Produces a typed [SystemState] item on the `state` data port.
 */
class ScreenTrigger : Trigger<SystemState> {

    override val definition = systemStateDefinition(
        typeId = "trigger.screen",
        displayName = "Screen On / Off",
        description = "Starts when the screen turns on or off",
        category = NodeCategory.DEVICE_STATE,
        eventFilterLabel = "Event",
        eventFilterOptions = listOf("on", "off"),
    )

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<SystemState>> =
        systemStateFlow(
            source = TriggerSource.DISPLAY,
            triggerType = "screen",
            node = node,
            host = host,
        )
}
