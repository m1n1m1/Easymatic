package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.items.SystemState
import com.example.ottomatic.engine.NodeOutput
import kotlinx.coroutines.flow.Flow

/**
 * Trigger for `trigger.user_present`. Fires when the user unlocks the device
 * (ACTION_USER_PRESENT).
 *
 * Produced by the runtime-registered [com.example.ottomatic.data.trigger.ScreenBroadcastBridge].
 *
 * Produces a typed [SystemState] item on the `state` data port.
 */
class UserPresentTrigger : Trigger<SystemState> {

    override val definition = systemStateDefinition(
        typeId = "trigger.user_present",
        displayName = "Device Unlocked",
        description = "Starts when the user unlocks the device",
        category = NodeCategory.DEVICE_STATE,
    )

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<SystemState>> =
        systemStateFlow(
            source = TriggerSource.DISPLAY,
            triggerType = "user_present",
            node = node,
            host = host,
        )
}
