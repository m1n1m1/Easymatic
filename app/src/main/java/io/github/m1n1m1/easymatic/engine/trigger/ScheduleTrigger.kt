package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.domain.model.DayFilter
import io.github.m1n1m1.easymatic.domain.model.MINUTES_PER_DAY
import io.github.m1n1m1.easymatic.domain.model.MINUTES_PER_HOUR
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.config.Hint
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.TimeOfDay
import io.github.m1n1m1.easymatic.domain.model.config.VisibleWhen
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.domain.model.minuteOfDay
import io.github.m1n1m1.easymatic.domain.model.items.ScheduleFire
import io.github.m1n1m1.easymatic.domain.model.schema.DateTime
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import java.util.Calendar

/** How `trigger.schedule` decides *when* to fire. */
@Serializable
enum class ScheduleMode {
    @Label("Repeat on an interval")
    INTERVAL,

    @Label("At a time of day")
    AT_TIME,
}

/** The unit of `trigger.schedule`'s repeat interval. */
@Serializable
enum class IntervalUnit(val minutes: Long) {
    @Label("Minutes")
    MINUTES(1L),

    @Label("Hours")
    HOURS(MINUTES_PER_HOUR.toLong()),

    @Label("Days")
    DAYS(MINUTES_PER_DAY.toLong()),
}

/**
 * Config for `trigger.schedule`.
 *
 * One class covers what used to be four separate nodes — the old
 * `trigger.schedule`, `trigger.sleep` (an interval plus a daily window),
 * `trigger.stopwatch` (an interval, reading elapsed time off the output) and
 * `trigger.time_tick` (a one-minute interval) — plus firing at a specific time
 * of day, which none of them could do. The mode-specific fields are hidden by
 * `@VisibleWhen`, so the form only ever shows the settings the chosen mode
 * actually reads.
 *
 * The day filters apply to *both* modes: an empty selection means "every day".
 * When an interval runs inside a [ScheduleWindow] that wraps past midnight, the
 * day filters are matched against the day the window *opened* on — see
 * [ScheduleWindow.anchorDay].
 */
@Suppress("LongParameterList") // One property per form field; a config class is a flat declaration.
@Serializable
data class ScheduleConfig(
    @Label("Mode") val mode: ScheduleMode = ScheduleMode.INTERVAL,

    @VisibleWhen("mode", "INTERVAL")
    @Label("Repeat every")
    @Hint("under 15 only fires while the device is awake")
    val every: Int = DEFAULT_INTERVAL_MINUTES.toInt(),

    @VisibleWhen("mode", "INTERVAL")
    @Label("Unit")
    val everyUnit: IntervalUnit = IntervalUnit.MINUTES,

    @VisibleWhen("mode", "AT_TIME")
    @Label("At")
    @TimeOfDay
    val atTime: String = "07:30",

    @Label("Mondays") val monday: Boolean = false,
    @Label("Tuesdays") val tuesday: Boolean = false,
    @Label("Wednesdays") val wednesday: Boolean = false,
    @Label("Thursdays") val thursday: Boolean = false,
    @Label("Fridays") val friday: Boolean = false,
    @Label("Saturdays") val saturday: Boolean = false,
    @Label("Sundays") val sunday: Boolean = false,
    @Label("Days of month")
    @Hint("e.g. 1,15, empty = every day")
    val daysOfMonth: String = "",

    @VisibleWhen("mode", "INTERVAL")
    @Label("Only during a time window")
    val windowEnabled: Boolean = false,

    @VisibleWhen("windowEnabled", "true")
    @Label("Active from")
    @TimeOfDay
    val windowFrom: String = "22:00",

    @VisibleWhen("windowEnabled", "true")
    @Label("Active until")
    @Hint("earlier than 'from' runs past midnight")
    @TimeOfDay
    val windowUntil: String = "07:00",
) {
    /**
     * The days this schedule is allowed to fire on — the seven checkboxes and
     * the days-of-month field read as one thing.
     *
     * Shared with `action.wait_until` rather than derived here, so "which day is
     * this?" is answered in one place for both.
     */
    val dayFilter: DayFilter
        get() = DayFilter.of(
            monday = monday,
            tuesday = tuesday,
            wednesday = wednesday,
            thursday = thursday,
            friday = friday,
            saturday = saturday,
            sunday = sunday,
            daysOfMonth = daysOfMonth,
        )

    /** The selected [Calendar] day-of-week constants; empty means "every day". */
    val daysOfWeek: Set<Int> get() = dayFilter.weekdays

    /** The selected days of the month, or an empty set meaning "every day". */
    val monthDays: Set<Int> get() = dayFilter.monthDays

    /** The repeat cadence in minutes, at least one. */
    val intervalMinutes: Long get() = (every.toLong() * everyUnit.minutes).coerceAtLeast(1L)

    /**
     * The active window, or null when the schedule runs around the clock.
     *
     * Null in [ScheduleMode.AT_TIME] regardless of [windowEnabled]: a single
     * daily time needs no window, and a stale toggle left over from the other
     * mode must not silently restrict it.
     */
    internal val window: ScheduleWindow?
        get() = if (!windowEnabled || mode != ScheduleMode.INTERVAL) {
            null
        } else {
            ScheduleWindow(minutesOfDay(windowFrom), minutesOfDay(windowUntil))
        }

    /**
     * True when the cadence is below WorkManager's floor and the trigger must
     * fall back to the minute-resolution `ACTION_TIME_TICK` broadcast.
     */
    val usesMinuteTicks: Boolean get() = intervalMinutes < DEFAULT_INTERVAL_MINUTES

    /**
     * True when [epochMs] passes the day filters and falls inside the active
     * window, if there is one.
     *
     * With a wrapping window the day check runs against
     * [ScheduleWindow.anchorDay] rather than the fire instant, so a 23:00–01:00
     * window selected for Saturdays keeps firing through Sunday 01:00 instead of
     * being truncated at midnight.
     */
    fun matches(epochMs: Long): Boolean {
        val window = window ?: return matchesDay(epochMs)
        return window.contains(minuteOfDay(epochMs)) && matchesDay(window.anchorDay(epochMs))
    }

    /** True when [epochMs] falls on a day the day-of-week/day-of-month filters accept. */
    fun matchesDay(epochMs: Long): Boolean = dayFilter.matches(epochMs)
}

/**
 * Trigger for `trigger.schedule` — the single time-based entry point.
 *
 * Three runtime paths, chosen from the config:
 *
 *  1. **Exact alarms**, re-armed one at a time after each fire. Used by
 *     [ScheduleMode.AT_TIME], and by any interval confined to a
 *     [ScheduleWindow] — there the slots are aligned to the window's start, so
 *     "every 30 minutes from 23:00" lands on 23:00, 23:30, 00:00, 00:30. This
 *     is the only path accurate to the minute, and for a windowed interval it
 *     also wakes the device *less* than the poller, which would otherwise run
 *     around the clock and discard most of its ticks.
 *  2. **A periodic [io.github.m1n1m1.easymatic.data.trigger.ScheduleWorker]** for an
 *     unwindowed interval of 15 minutes or more, so it keeps running when the
 *     app is killed. Fire times are phased to whenever it was armed.
 *  3. **The system `ACTION_TIME_TICK` broadcast** for intervals under 15
 *     minutes, which cannot use WorkManager at all (that is its hard floor).
 *     Every *n*-th minute is kept, counted from the window start when there is
 *     one so this path phases like path 1. The broadcast only arrives while the
 *     device is awake, which the field label states.
 *
 * All three then apply the same day and window filters, and emit a typed
 * [ScheduleFire] on the `fireTime` data port.
 */
class ScheduleTrigger : Trigger<ScheduleConfig, ScheduleFire> {

    override val definition = triggerNode<ScheduleConfig, ScheduleFire>(
        typeId = "trigger.schedule",
        displayName = "Schedule",
        description = "Starts the workflow on an interval or at a time of day",
        category = NodeCategory.TIME_SCHEDULE,
        icon = NodeIcon.SCHEDULE,
        output = dataOut<ScheduleFire>("fireTime", label = "Fire time"),
    )

    override fun activate(
        config: ScheduleConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<ScheduleFire>> = when {
        config.mode == ScheduleMode.AT_TIME -> alarmFlow(config, node, host)
        config.usesMinuteTicks -> minuteTickFlow(config, host)
        config.window != null -> alarmFlow(config, node, host)
        else -> pollFlow(config, node, host)
    }

    /** Path 1: re-armed one-shot exact alarms at each computed fire time. */
    private fun alarmFlow(
        config: ScheduleConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<ScheduleFire>> = flow {
        val session = FireSession()
        var handle: ScheduleHandle? = null
        try {
            while (true) {
                val at = nextFireTime(config, System.currentTimeMillis()) ?: break
                handle?.cancel()
                handle = host.armAlarm(node.id, at)
                val fired = host.busEvents()
                    .filter { it.source == TriggerSource.SCHEDULE && it.triggerNodeId == node.id }
                    .first()
                emit(NodeOutput(session.fire(fired.firedAtEpochMs)))
            }
        } finally {
            handle?.cancel()
        }
    }

    /** Path 2: WorkManager-backed periodic poll — unwindowed, 15 minutes or slower. */
    private fun pollFlow(
        config: ScheduleConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<ScheduleFire>> = flow {
        val session = FireSession()
        val handle = host.armSchedule(node.id, config.intervalMinutes)
        try {
            host.busEvents()
                .filter { it.source == TriggerSource.SCHEDULE && it.triggerNodeId == node.id }
                .filter { config.matches(it.firedAtEpochMs) }
                .collect { bus -> emit(NodeOutput(session.fire(bus.firedAtEpochMs))) }
        } finally {
            handle.cancel()
        }
    }

    /**
     * Path 3: sub-15-minute cadence off the system minute tick. Nothing is
     * armed, so nothing needs tearing down — the broadcast is already published
     * by `ScreenBroadcastBridge` for as long as the host lives.
     *
     * Ticks are counted from the window start when there is one, and from
     * midnight otherwise, so this path lands on the same boundaries the
     * alarm-driven path would have picked.
     */
    private fun minuteTickFlow(
        config: ScheduleConfig,
        host: TriggerHost,
    ): Flow<NodeOutput<ScheduleFire>> = flow {
        val session = FireSession()
        val everyMinutes = config.intervalMinutes.toInt()
        val offset = config.window?.startMinute ?: 0
        host.busEvents()
            .filter { it.source == TriggerSource.SYSTEM && it.payload[KEY_TRIGGER_TYPE] == TIME_TICK_TYPE }
            .filter { Math.floorMod(minuteOfDay(it.firedAtEpochMs) - offset, everyMinutes) == 0 }
            .filter { config.matches(it.firedAtEpochMs) }
            .collect { bus -> emit(NodeOutput(session.fire(bus.firedAtEpochMs))) }
    }

    /**
     * Per-collection counters behind [ScheduleFire.elapsedMs] and
     * [ScheduleFire.count]. Both are relative to when the flow was collected, so
     * they restart on re-arm and on process death — the same caveat the old
     * `trigger.stopwatch` carried.
     */
    private class FireSession {
        private val startedAt = System.currentTimeMillis()
        private var count = 0

        fun fire(firedAt: Long): ScheduleFire {
            count += 1
            val calendar = Calendar.getInstance().apply { timeInMillis = firedAt }
            return ScheduleFire(
                firedAt = DateTime(firedAt),
                elapsedMs = firedAt - startedAt,
                count = count,
                hour = calendar.get(Calendar.HOUR_OF_DAY),
                minute = calendar.get(Calendar.MINUTE),
                dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK),
                dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH),
            )
        }
    }

    private companion object {
        /** The `triggerType` payload `ScreenBroadcastBridge` puts on minute ticks. */
        const val TIME_TICK_TYPE = "time_tick"
    }
}
