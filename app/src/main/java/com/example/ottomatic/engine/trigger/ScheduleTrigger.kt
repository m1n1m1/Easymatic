package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.WorkflowNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Trigger for `trigger.schedule`. Arms a periodic [ScheduleWorker] via the
 * host when collection starts, surfaces matching bus events, and cancels the
 * schedule when the flow is cancelled.
 */
class ScheduleTrigger : Trigger {

    override val typeId: String = TYPE_ID

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<TriggerEvent> {
        val intervalRaw = node.config["interval"]
        val cron = if (intervalRaw == "cron") node.config["cron"] else null
        val intervalMinutes = intervalRaw?.toLongOrNull() ?: DEFAULT_INTERVAL_MINUTES
        return flow {
            val handle = host.armSchedule(node.id, intervalMinutes, cron)
            try {
                host.busEvents()
                    .filter { it.source == TriggerSource.SCHEDULE && it.triggerNodeId == node.id }
                    .collect { emit(TriggerEvent(triggerNodeId = node.id)) }
            } finally {
                handle.cancel()
            }
        }
    }

    companion object {
        const val TYPE_ID = "trigger.schedule"
        private const val DEFAULT_INTERVAL_MINUTES = 15L
    }
}
