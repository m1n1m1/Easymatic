package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.WorkflowNode
import kotlinx.coroutines.flow.Flow

/**
 * Trigger for `trigger.screen`. Fires when the screen turns on or off.
 *
 * Produced by the runtime-registered [com.example.ottomatic.data.trigger.ScreenBroadcastBridge].
 *
 * Produces a typed [com.example.ottomatic.domain.model.items.SystemState] item
 * on the `state` data port.
 */
class ScreenTrigger : Trigger {

    override val typeId: String = TYPE_ID

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<TriggerEvent> =
        systemStateFlow(
            source = TriggerSource.DISPLAY,
            triggerType = "screen",
            node = node,
            host = host,
        )

    companion object {
        const val TYPE_ID = "trigger.screen"
    }
}
