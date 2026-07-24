package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import java.util.Calendar

/**
 * Trigger for `trigger.sleep`. Arms a periodic schedule via the host and fires
 * only while the device's wall-clock time falls inside the configured daily
 * window `[startTime, endTime)`. Outside the window the ticks are suppressed,
 * so connected actions run repeatedly but only during the "sleep" period.
 *
 * Config keys:
 * - `startTime` (STRING, `HH:mm`) — window start (default `22:00`).
 * - `endTime` (STRING, `HH:mm`) — window end (default `07:00`). If the end is
 *   earlier than the start the window wraps past midnight.
 * - `intervalMinutes` (LONG) — tick cadence (default 15, clamped to the
 *   WorkManager floor by the host).
 *
 * Produces no typed data output — only the EXECUTION pulse.
 */
class SleepTrigger : Trigger<Unit> {

    override val definition = triggerNode<Unit>(
        typeId = "trigger.sleep",
        displayName = "Sleep",
        description = "Ticks repeatedly but only during a configured daily time window",
        category = NodeCategory.TIME_SCHEDULE,
        iconKey = "timer",
        encodeData = { emptyMap() },
    )

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<Unit>> {
        val startTime = node.config[CONFIG_START_TIME]?.takeIf { it.isNotBlank() } ?: DEFAULT_START_TIME
        val endTime = node.config[CONFIG_END_TIME]?.takeIf { it.isNotBlank() } ?: DEFAULT_END_TIME
        val intervalMinutes = node.config[CONFIG_INTERVAL]?.toLongOrNull() ?: DEFAULT_INTERVAL_MINUTES
        val start = parseTime(startTime)
        val end = parseTime(endTime)
        return flow {
            val handle = host.armSchedule(node.id, intervalMinutes, cron = null)
            try {
                host.busEvents()
                    .filter {
                        it.source == TriggerSource.SCHEDULE && it.triggerNodeId == node.id
                    }
                    .filter { isInWindow(it.firedAtEpochMs, start, end) }
                    .collect {
                        emit(NodeOutput(Unit))
                    }
            } finally {
                handle.cancel()
            }
        }
    }

    private fun parseTime(raw: String): Int {
        val parts = raw.split(':')
        val hours = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minutes = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return hours * MINUTES_PER_HOUR + minutes
    }

    private fun isInWindow(epochMs: Long, startMinutes: Int, endMinutes: Int): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = epochMs }
        val now = cal.get(Calendar.HOUR_OF_DAY) * MINUTES_PER_HOUR + cal.get(Calendar.MINUTE)
        return if (startMinutes <= endMinutes) {
            now in startMinutes until endMinutes
        } else {
            now >= startMinutes || now < endMinutes
        }
    }

    companion object {
        const val CONFIG_START_TIME = "startTime"
        const val CONFIG_END_TIME = "endTime"
        const val CONFIG_INTERVAL = "intervalMinutes"

        const val DEFAULT_START_TIME = "22:00"
        const val DEFAULT_END_TIME = "07:00"
        const val DEFAULT_INTERVAL_MINUTES = 15L
        private const val MINUTES_PER_HOUR = 60
    }
}
