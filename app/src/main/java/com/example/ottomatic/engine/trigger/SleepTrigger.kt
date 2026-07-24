package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.pulseTriggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable

/**
 * Config for `trigger.sleep`. If [endTime] is earlier than [startTime] the
 * window wraps past midnight.
 *
 * These three settings were previously read from the node's config map without
 * being declared, so they could not be edited in the generated form at all;
 * deriving the form from this class makes that impossible.
 */
@Serializable
data class SleepConfig(
    @Label("Start time (HH:mm)") val startTime: String = "22:00",
    @Label("End time (HH:mm)") val endTime: String = "07:00",
    @Label("Tick interval (minutes, minimum 15)") val intervalMinutes: Long = DEFAULT_INTERVAL_MINUTES,
) {
    /** Minutes past midnight of [startTime]. */
    val startMinutes: Int get() = minutesOfDay(startTime)

    /** Minutes past midnight of [endTime]. */
    val endMinutes: Int get() = minutesOfDay(endTime)
}

/**
 * Trigger for `trigger.sleep`. Arms a periodic schedule via the host and fires
 * only while the device's wall-clock time falls inside the configured daily
 * window `[startTime, endTime)`. Outside the window the ticks are suppressed,
 * so connected actions run repeatedly but only during the "sleep" period.
 *
 * Produces no typed data output — only the EXECUTION pulse.
 */
class SleepTrigger : Trigger<SleepConfig, Unit> {

    override val definition = pulseTriggerNode<SleepConfig>(
        typeId = "trigger.sleep",
        displayName = "Sleep",
        description = "Ticks repeatedly but only during a configured daily time window",
        category = NodeCategory.TIME_SCHEDULE,
        icon = NodeIcon.TIMER,
    )

    override fun activate(config: SleepConfig, node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<Unit>> = flow {
        val handle = host.armSchedule(node.id, config.intervalMinutes, cron = null)
        try {
            host.busEvents()
                .filter { it.source == TriggerSource.SCHEDULE && it.triggerNodeId == node.id }
                .filter { isInWindow(it.firedAtEpochMs, config.startMinutes, config.endMinutes) }
                .collect { emit(NodeOutput(Unit)) }
        } finally {
            handle.cancel()
        }
    }

    private fun isInWindow(epochMs: Long, startMinutes: Int, endMinutes: Int): Boolean {
        val now = minutesOfDay(epochMs)
        return if (startMinutes <= endMinutes) {
            now in startMinutes until endMinutes
        } else {
            now >= startMinutes || now < endMinutes
        }
    }
}
