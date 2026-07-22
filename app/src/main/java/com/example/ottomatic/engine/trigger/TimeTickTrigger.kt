package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.WorkflowNode
import kotlinx.coroutines.flow.Flow

/**
 * Trigger for `trigger.time_tick`. Fires roughly every minute
 * (ACTION_TIME_TICK). Produced by the runtime-registered
 * [com.example.ottomatic.data.trigger.ScreenBroadcastBridge].
 *
 * Produces a typed [com.example.ottomatic.domain.model.items.SystemState] item
 * on the `state` data port.
 */
class TimeTickTrigger : Trigger {

    override val typeId: String = TYPE_ID

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<TriggerEvent> =
        systemStateFlow(
            source = TriggerSource.SYSTEM,
            triggerType = "time_tick",
            node = node,
            host = host,
        )

    companion object {
        const val TYPE_ID = "trigger.time_tick"
    }
}
