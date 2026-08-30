package io.github.m1n1m1.easymatic.domain.model

import java.util.Calendar

/** Minutes in an hour, and in a day. */
const val MINUTES_PER_HOUR = 60
const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR

/** Upper bound on a forward day scan — a full leap year. */
const val MAX_DAYS_SCANNED = 366

/**
 * Which calendar days a repeating time-of-day rule is allowed to land on.
 *
 * Lives here rather than beside the schedule trigger for the reason [TimeOfDay]
 * does: two nodes read it — `trigger.schedule` ("fire at 07:30 on weekdays") and
 * `action.wait_until` ("wait until the next 07:30 that is a weekday") — and a
 * second copy of "which day is this?" would eventually disagree with the first.
 * It is also a pure function of a timestamp, so it gets JVM tests where the
 * platform half would need a device.
 *
 * Both sets are **empty means every day**, and they combine with AND: selecting
 * Mondays and day-of-month 1 means "the first of the month, when that is a
 * Monday". An empty [weekdays] with a [monthDays] no month has — the 31st of
 * February — is a filter nothing satisfies, which is why every scan through this
 * is bounded by [MAX_DAYS_SCANNED] and answers null rather than looping.
 */
data class DayFilter(
    val weekdays: Set<Int> = emptySet(),
    val monthDays: Set<Int> = emptySet(),
) {

    /** True when [epochMs] falls on a day both filters accept. */
    fun matches(epochMs: Long): Boolean {
        val weekOk = weekdays.isEmpty() || dayOfWeek(epochMs) in weekdays
        val monthOk = monthDays.isEmpty() || dayOfMonth(epochMs) in monthDays
        return weekOk && monthOk
    }

    companion object {

        /**
         * The filter a config form's seven checkboxes and days-of-month field
         * describe.
         *
         * One factory rather than a property on each config class, so the
         * [Calendar] constant mapping and the CSV parse exist once — the shape
         * both `ScheduleConfig` and `WaitUntilConfig` delegate to.
         */
        @Suppress("LongParameterList") // One parameter per checkbox; the week sets the count.
        fun of(
            monday: Boolean = false,
            tuesday: Boolean = false,
            wednesday: Boolean = false,
            thursday: Boolean = false,
            friday: Boolean = false,
            saturday: Boolean = false,
            sunday: Boolean = false,
            daysOfMonth: String = "",
        ): DayFilter = DayFilter(
            weekdays = buildSet {
                if (sunday) add(Calendar.SUNDAY)
                if (monday) add(Calendar.MONDAY)
                if (tuesday) add(Calendar.TUESDAY)
                if (wednesday) add(Calendar.WEDNESDAY)
                if (thursday) add(Calendar.THURSDAY)
                if (friday) add(Calendar.FRIDAY)
                if (saturday) add(Calendar.SATURDAY)
            },
            monthDays = monthDaysOf(daysOfMonth),
        )

        /** The days named by a `1,15` style list; anything unreadable is dropped. */
        fun monthDaysOf(text: String): Set<Int> =
            text.split(',').mapNotNullTo(mutableSetOf()) { it.trim().toIntOrNull() }
    }
}

/** Minutes past midnight of an epoch timestamp, in the device's timezone. */
fun minuteOfDay(epochMs: Long): Int {
    val calendar = Calendar.getInstance().apply { timeInMillis = epochMs }
    return calendar.get(Calendar.HOUR_OF_DAY) * MINUTES_PER_HOUR + calendar.get(Calendar.MINUTE)
}

/** The [Calendar] day-of-week of an epoch timestamp. */
fun dayOfWeek(epochMs: Long): Int =
    Calendar.getInstance().apply { timeInMillis = epochMs }.get(Calendar.DAY_OF_WEEK)

/** The day of the month (1-31) of an epoch timestamp. */
fun dayOfMonth(epochMs: Long): Int =
    Calendar.getInstance().apply { timeInMillis = epochMs }.get(Calendar.DAY_OF_MONTH)

/**
 * The next moment strictly after [fromEpochMs] at which the clock reads
 * [minuteOfDay] on a day [filter] accepts, or null when no day within
 * [MAX_DAYS_SCANNED] satisfies it.
 *
 * Candidate days are stepped through a [Calendar] rather than by adding 24
 * hours, so the daily anchor follows the device timezone's DST transitions.
 *
 * Null is a real answer, not a failure: a filter combination no day can meet
 * means the caller arms nothing rather than looping forever.
 */
fun nextTimeOfDay(minuteOfDay: Int, filter: DayFilter, fromEpochMs: Long): Long? {
    val calendar = startOfDayAt(fromEpochMs, minuteOfDay)
    // Today's slot may already have passed; the first candidate is tomorrow then.
    if (calendar.timeInMillis <= fromEpochMs) calendar.add(Calendar.DAY_OF_YEAR, 1)
    repeat(MAX_DAYS_SCANNED) {
        if (filter.matches(calendar.timeInMillis)) return calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_YEAR, 1)
    }
    return null
}

/** [epochMs]'s own day, at [minuteOfDay] past midnight, seconds cleared. */
fun startOfDayAt(epochMs: Long, minuteOfDay: Int): Calendar =
    Calendar.getInstance().apply {
        timeInMillis = epochMs
        set(Calendar.HOUR_OF_DAY, minuteOfDay / MINUTES_PER_HOUR)
        set(Calendar.MINUTE, minuteOfDay % MINUTES_PER_HOUR)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
