package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.ScheduleFire
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import java.util.Calendar

/**
 * How often `trigger.schedule` fires. [CRON] defers to the node's cron
 * expression instead of a fixed cadence.
 */
@Serializable
enum class ScheduleInterval(val minutes: Long?) {
    @Label("Every 15 minutes")
    EVERY_15_MINUTES(15L),

    @Label("Every 30 minutes")
    EVERY_30_MINUTES(30L),

    @Label("Hourly")
    HOURLY(60L),

    @Label("Every 6 hours")
    EVERY_6_HOURS(360L),

    @Label("Every 12 hours")
    EVERY_12_HOURS(720L),

    @Label("Daily")
    DAILY(1_440L),

    @Label("Cron expression")
    CRON(null),
}

/**
 * Config for `trigger.schedule`.
 *
 * The day filters are independent switches rather than the comma-joined strings
 * they used to be, and the four window settings — which the trigger read without
 * declaring them — are now part of the generated form.
 */
@Suppress("LongParameterList") // One property per form field; a config class is a flat declaration.
@Serializable
data class ScheduleConfig(
    @Label("Interval") val interval: ScheduleInterval = ScheduleInterval.EVERY_15_MINUTES,
    @Label("Cron expression (when interval is cron)") val cron: String = "*/15 * * * *",
    @Label("Mondays") val monday: Boolean = false,
    @Label("Tuesdays") val tuesday: Boolean = false,
    @Label("Wednesdays") val wednesday: Boolean = false,
    @Label("Thursdays") val thursday: Boolean = false,
    @Label("Fridays") val friday: Boolean = false,
    @Label("Saturdays") val saturday: Boolean = false,
    @Label("Sundays") val sunday: Boolean = false,
    @Label("Days of month (e.g. 1,15, empty = every day)") val daysOfMonth: String = "",
    @Label("Not before (HH:mm, optional)") val notBefore: String = "",
    @Label("Not after (HH:mm, optional)") val notAfter: String = "",
) {
    /**
     * The selected [Calendar] day-of-week constants, or an empty set meaning
     * "every day" (no day filter).
     */
    val daysOfWeek: Set<Int>
        get() = buildSet {
            if (sunday) add(Calendar.SUNDAY)
            if (monday) add(Calendar.MONDAY)
            if (tuesday) add(Calendar.TUESDAY)
            if (wednesday) add(Calendar.WEDNESDAY)
            if (thursday) add(Calendar.THURSDAY)
            if (friday) add(Calendar.FRIDAY)
            if (saturday) add(Calendar.SATURDAY)
        }

    /** The selected days of the month, or an empty set meaning "every day". */
    val monthDays: Set<Int>
        get() = daysOfMonth.split(',').mapNotNullTo(mutableSetOf()) { it.trim().toIntOrNull() }

    /** The cron expression to arm, or null when a fixed interval is used. */
    val cronExpression: String? get() = cron.takeIf { interval == ScheduleInterval.CRON && it.isNotBlank() }

    /** The cadence to arm, falling back to the WorkManager floor for cron schedules. */
    val intervalMinutes: Long get() = interval.minutes ?: DEFAULT_INTERVAL_MINUTES

    /** True when [epochMs] passes the day and time-of-day filters. */
    fun matches(epochMs: Long): Boolean = matchesDay(epochMs) && matchesTimeOfDay(epochMs)

    private fun matchesDay(epochMs: Long): Boolean {
        val weekdays = daysOfWeek
        val monthDays = monthDays
        val weekOk = weekdays.isEmpty() || dayOfWeek(epochMs) in weekdays
        val monthOk = monthDays.isEmpty() || dayOfMonth(epochMs) in monthDays
        return weekOk && monthOk
    }

    private fun matchesTimeOfDay(epochMs: Long): Boolean {
        val start = notBefore.takeIf { it.isNotBlank() }?.let { minutesOfDay(it) }
        val end = notAfter.takeIf { it.isNotBlank() }?.let { minutesOfDay(it) }
        if (start == null && end == null) return true
        val now = minutesOfDay(epochMs)
        val from = start ?: 0
        val until = end ?: MINUTES_PER_DAY
        return if (from <= until) now in from until until else now >= from || now < until
    }
}

// A `@Serializable` class must not declare its own companion: the serialization
// plugin puts `serializer()` on it, and a private companion would hide it.
private const val MINUTES_PER_DAY = 24 * 60

/**
 * Trigger for `trigger.schedule`. Arms a periodic
 * [com.example.ottomatic.data.trigger.ScheduleWorker] via the host when
 * collection starts, surfaces matching bus events, and cancels the schedule
 * when the flow is cancelled.
 *
 * Optional day and time-of-day filters (mirroring MacroDroid's Day/Time and
 * Day-of-Week triggers) suppress ticks outside the configured window.
 *
 * Produces a typed [ScheduleFire] item on the `fireTime` data port.
 */
class ScheduleTrigger : Trigger<ScheduleConfig, ScheduleFire> {

    override val definition = triggerNode<ScheduleConfig, ScheduleFire>(
        typeId = "trigger.schedule",
        displayName = "Schedule",
        description = "Starts the workflow on a fixed schedule",
        category = NodeCategory.TIME_SCHEDULE,
        icon = NodeIcon.SCHEDULE,
        output = dataOut<ScheduleFire>("fireTime", label = "Fire time"),
    )

    override fun activate(
        config: ScheduleConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<ScheduleFire>> = flow {
        val handle = host.armSchedule(node.id, config.intervalMinutes, config.cronExpression)
        try {
            host.busEvents()
                .filter { it.source == TriggerSource.SCHEDULE && it.triggerNodeId == node.id }
                .filter { config.matches(it.firedAtEpochMs) }
                .collect { bus -> emit(NodeOutput(ScheduleFire(firedAt = bus.firedAtEpochMs))) }
        } finally {
            handle.cancel()
        }
    }
}
