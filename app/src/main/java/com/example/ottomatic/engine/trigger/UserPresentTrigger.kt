package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.WorkflowNode
import kotlinx.coroutines.flow.Flow

/**
 * Trigger for `trigger.user_present`. Fires when the user unlocks the device
 * (ACTION_USER_PRESENT).
 *
 * Produced by the runtime-registered [com.example.ottomatic.data.trigger.ScreenBroadcastBridge].
 *
 * Produces a typed [com.example.ottomatic.domain.model.items.SystemState] item
 * on the `state` data port.
 */
class UserPresentTrigger : Trigger {

    override val typeId: String = TYPE_ID

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<TriggerEvent> =
        systemStateFlow(
            source = TriggerSource.DISPLAY,
            triggerType = "user_present",
            node = node,
            host = host,
        )

    companion object {
        const val TYPE_ID = "trigger.user_present"
    }
}
