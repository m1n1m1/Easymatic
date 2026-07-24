package com.example.ottomatic.engine.trigger

import java.util.Calendar

/**
 * Shared helpers for the schedule-backed triggers (`trigger.schedule`,
 * `trigger.sleep`, `trigger.stopwatch`), all of which arm a periodic
 * [TriggerHost.armSchedule] poll and filter its ticks against a wall-clock
 * window.
 */

/** WorkManager's periodic-work floor, and the default cadence for every poller. */
internal const val DEFAULT_INTERVAL_MINUTES = 15L

private const val MINUTES_PER_HOUR = 60

/** Minutes past midnight of an `HH:mm` string; 0 when unparseable. */
internal fun minutesOfDay(hhmm: String): Int {
    val parts = hhmm.split(':')
    val hours = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
    val minutes = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
    return hours * MINUTES_PER_HOUR + minutes
}

/** Minutes past midnight of an epoch timestamp, in the device's timezone. */
internal fun minutesOfDay(epochMs: Long): Int {
    val calendar = Calendar.getInstance().apply { timeInMillis = epochMs }
    return calendar.get(Calendar.HOUR_OF_DAY) * MINUTES_PER_HOUR + calendar.get(Calendar.MINUTE)
}

/** The [Calendar] day-of-week / day-of-month of an epoch timestamp. */
internal fun dayOfWeek(epochMs: Long): Int =
    Calendar.getInstance().apply { timeInMillis = epochMs }.get(Calendar.DAY_OF_WEEK)

/** The day of the month (1-31) of an epoch timestamp. */
internal fun dayOfMonth(epochMs: Long): Int =
    Calendar.getInstance().apply { timeInMillis = epochMs }.get(Calendar.DAY_OF_MONTH)
