package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.ScheduleFire
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import java.util.Calendar

/**
 * Trigger for `trigger.schedule`. Arms a periodic [ScheduleWorker] via the
 * host when collection starts, surfaces matching bus events, and cancels the
 * schedule when the flow is cancelled.
 *
 * Optional day/time filters (mirroring MacroDroid's Day/Time and Day-of-Week
 * triggers) suppress ticks that fall outside the configured window:
 * - `daysOfWeek` — comma-separated `mon,tue,...` (empty = every day).
 * - `daysOfMonth` — comma-separated day numbers `1,15,...` (empty = every day).
 * - `timeOfDay` — `HH:mm`; when set, only ticks at/after this time fire.
 * - `endTimeOfDay` — `HH:mm`; when set, only ticks before this time fire.
 *   If `endTimeOfDay` is earlier than `timeOfDay` the window wraps past midnight.
 *
 * Produces a typed [ScheduleFire] item on the `fireTime` data port.
 */
class ScheduleTrigger : Trigger<ScheduleFire> {

    override val definition = triggerNode<ScheduleFire>(
        typeId = "trigger.schedule",
        displayName = "Schedule",
        description = "Starts the workflow on a fixed schedule",
        category = NodeCategory.TIME_SCHEDULE,
        iconKey = "schedule",
        dataOutputs = listOf(dataOut<ScheduleFire>("fireTime")),
        configFields = listOf(
            ConfigField(
                key = "interval",
                label = "Interval",
                type = ConfigFieldType.ENUM(options = listOf("15", "30", "60", "360", "720", "1440", "cron")),
                defaultValue = "15",
            ),
            ConfigField(
                key = "cron",
                label = "Cron expression (when interval = cron)",
                type = ConfigFieldType.STR,
                defaultValue = "*/15 * * * *",
            ),
        ),
        encodeData = { fire -> mapOf("fireTime" to Item.of(fire)) },
    )

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<ScheduleFire>> {
        val intervalRaw = node.config["interval"]
        val cron = if (intervalRaw == "cron") node.config["cron"] else null
        val intervalMinutes = intervalRaw?.toLongOrNull() ?: DEFAULT_INTERVAL_MINUTES
        val daysOfWeek = parseDaysOfWeek(node.config[CONFIG_DAYS_OF_WEEK])
        val daysOfMonth = parseIntSet(node.config[CONFIG_DAYS_OF_MONTH])
        val timeOfDay = node.config[CONFIG_TIME_OF_DAY]?.takeIf { it.isNotBlank() }
        val endTimeOfDay = node.config[CONFIG_END_TIME_OF_DAY]?.takeIf { it.isNotBlank() }
        return flow {
            val handle = host.armSchedule(node.id, intervalMinutes, cron)
            try {
                host.busEvents()
                    .filter { it.source == TriggerSource.SCHEDULE && it.triggerNodeId == node.id }
                    .filter { matchesDayFilter(it.firedAtEpochMs, daysOfWeek, daysOfMonth) }
                    .filter { matchesTimeFilter(it.firedAtEpochMs, timeOfDay, endTimeOfDay) }
                    .collect { bus ->
                        emit(NodeOutput(ScheduleFire(firedAt = bus.firedAtEpochMs)))
                    }
            } finally {
                handle.cancel()
            }
        }
    }

    private fun parseDaysOfWeek(raw: String?): Set<Int> {
        if (raw.isNullOrBlank()) return emptySet()
        val map = mapOf(
            "sun" to Calendar.SUNDAY, "mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY,
            "wed" to Calendar.WEDNESDAY, "thu" to Calendar.THURSDAY, "fri" to Calendar.FRIDAY,
            "sat" to Calendar.SATURDAY,
        )
        return raw.split(',').mapNotNull { token -> map[token.trim().lowercase()] }.toSet()
    }

    private fun parseIntSet(raw: String?): Set<Int> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
    }

    private fun matchesDayFilter(epochMs: Long, daysOfWeek: Set<Int>, daysOfMonth: Set<Int>): Boolean {
        if (daysOfWeek.isEmpty() && daysOfMonth.isEmpty()) return true
        val cal = Calendar.getInstance().apply { timeInMillis = epochMs }
        val dayOk = daysOfWeek.isEmpty() || cal.get(Calendar.DAY_OF_WEEK) in daysOfWeek
        val monthOk = daysOfMonth.isEmpty() || cal.get(Calendar.DAY_OF_MONTH) in daysOfMonth
        return dayOk && monthOk
    }

    private fun matchesTimeFilter(epochMs: Long, startTime: String?, endTime: String?): Boolean {
        if (startTime == null && endTime == null) return true
        val cal = Calendar.getInstance().apply { timeInMillis = epochMs }
        val now = cal.get(Calendar.HOUR_OF_DAY) * MINUTES_PER_HOUR + cal.get(Calendar.MINUTE)
        val start = startTime?.let { parseTime(it) } ?: 0
        val end = endTime?.let { parseTime(it) } ?: MINUTES_PER_DAY
        return if (start <= end) {
            now in start until end
        } else {
            now >= start || now < end
        }
    }

    private fun parseTime(raw: String): Int {
        val parts = raw.split(':')
        val hours = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minutes = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return hours * MINUTES_PER_HOUR + minutes
    }

    companion object {
        private const val DEFAULT_INTERVAL_MINUTES = 15L
        private const val MINUTES_PER_HOUR = 60
        private const val MINUTES_PER_DAY = 24 * 60

        const val CONFIG_DAYS_OF_WEEK = "daysOfWeek"
        const val CONFIG_DAYS_OF_MONTH = "daysOfMonth"
        const val CONFIG_TIME_OF_DAY = "timeOfDay"
        const val CONFIG_END_TIME_OF_DAY = "endTimeOfDay"
    }
}
