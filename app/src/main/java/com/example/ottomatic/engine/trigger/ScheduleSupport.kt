package com.example.ottomatic.engine.trigger

import com.example.ottomatic.domain.model.MAX_DAYS_SCANNED
import com.example.ottomatic.domain.model.MINUTES_PER_DAY
import com.example.ottomatic.domain.model.TimeOfDay
import com.example.ottomatic.domain.model.minuteOfDay
import com.example.ottomatic.domain.model.nextTimeOfDay
import com.example.ottomatic.domain.model.startOfDayAt
import java.util.Calendar

/**
 * Wall-clock helpers specific to `trigger.schedule`: the [ScheduleWindow] its
 * ticks are filtered against, and the next-fire-time search behind both the "at
 * a time of day" mode and the exact, window-aligned interval mode.
 *
 * The day filters themselves are **not** here. They live in
 * [com.example.ottomatic.domain.model.DayFilter], because `action.wait_until`
 * reads them too and one copy of "which day is this?" is the point.
 */

/** WorkManager's periodic-work floor, and the default cadence for the poller. */
internal const val DEFAULT_INTERVAL_MINUTES = 15L

/** Milliseconds in a minute, for offsetting interval slots within a window. */
private const val MS_PER_MINUTE = 60_000L

/**
 * Minutes past midnight of an `HH:mm` string; 0 when unparseable.
 *
 * Reads through [TimeOfDay], the same parser the config form's clock face writes
 * with, so what the picker stores and what the window compares can no longer
 * disagree — and so `25:99` is a clamped 23:59 rather than 1 599 minutes past
 * midnight, which is not a time of day and made the window silently unsatisfiable.
 */
internal fun minutesOfDay(hhmm: String): Int =
    TimeOfDay.parse(hhmm)?.minutesPastMidnight ?: 0

/**
 * A daily active window, in minutes past midnight.
 *
 * An [endMinute] at or before [startMinute] means the window **wraps past
 * midnight**, and the whole of it belongs to the day it *starts* on:
 * "Saturdays, 23:00–01:00" runs Saturday 23:00 through Sunday 01:00 as one
 * stretch. Attributing the post-midnight tail to the following day instead
 * would cut every overnight window in half the moment a day filter is set.
 *
 * A zero-length window (`start == end`) wraps by that rule and so covers the
 * full day, which is the harmless reading — never firing would be worse.
 */
internal data class ScheduleWindow(val startMinute: Int, val endMinute: Int) {

    /** True when the window runs past midnight into the next calendar day. */
    val wraps: Boolean get() = endMinute <= startMinute

    /** How long the window stays open, in minutes. */
    val lengthMinutes: Int
        get() = if (wraps) MINUTES_PER_DAY - startMinute + endMinute else endMinute - startMinute

    /** True when [minuteOfDay] falls inside the window; the end is exclusive. */
    fun contains(minuteOfDay: Int): Boolean =
        if (wraps) {
            minuteOfDay >= startMinute || minuteOfDay < endMinute
        } else {
            minuteOfDay in startMinute until endMinute
        }

    /**
     * [epochMs] shifted back a day when it lands in a wrapping window's
     * post-midnight tail, so day filters are applied to the day the window
     * *opened* on rather than the day the clock happens to read.
     *
     * The shift goes through [Calendar.add] rather than subtracting a fixed 24
     * hours, so it stays correct across DST transitions.
     */
    fun anchorDay(epochMs: Long): Long {
        if (!wraps || minuteOfDay(epochMs) >= startMinute) return epochMs
        return Calendar.getInstance().apply {
            timeInMillis = epochMs
            add(Calendar.DAY_OF_YEAR, -1)
        }.timeInMillis
    }
}

/**
 * The next epoch ms strictly after [fromEpochMs] at which [config] should fire,
 * or null when no day within a year satisfies its filters — which happens for a
 * combination that can never be met, such as a day-of-month no month has. The
 * caller then arms nothing rather than looping forever.
 *
 * Two shapes are covered:
 *  - [ScheduleMode.AT_TIME] fires once per matching day, at `atTime`. That half
 *    is [nextTimeOfDay], shared with `action.wait_until`.
 *  - [ScheduleMode.INTERVAL] with an active window fires on slots offset from
 *    the window's start, so "every 30 minutes from 23:00" lands on 23:00,
 *    23:30, 00:00, 00:30 rather than on whatever phase the poller was armed at.
 */
internal fun nextFireTime(config: ScheduleConfig, fromEpochMs: Long): Long? {
    val window = config.window
    return if (config.mode == ScheduleMode.AT_TIME || window == null) {
        nextTimeOfDay(minutesOfDay(config.atTime), config.dayFilter, fromEpochMs)
    } else {
        nextWindowSlot(config, window, fromEpochMs)
    }
}

/**
 * The next interval slot inside [window]. Walks candidate window *openings*
 * forward and, for each accepted one, jumps straight to the first slot after
 * [fromEpochMs] instead of stepping through every slot.
 *
 * Candidate days are stepped through a [Calendar], so the daily anchor follows
 * the device timezone's DST transitions. Slots *within* a window are offset in
 * raw milliseconds, so a DST change mid-window shifts the later slots by an
 * hour — not worth the complexity of correcting.
 */
private fun nextWindowSlot(config: ScheduleConfig, window: ScheduleWindow, fromEpochMs: Long): Long? {
    val intervalMs = config.intervalMinutes * MS_PER_MINUTE
    // The window containing `from` may have opened yesterday, so start there.
    val opening = startOfDayAt(fromEpochMs, window.startMinute).apply {
        if (timeInMillis > fromEpochMs) add(Calendar.DAY_OF_YEAR, -1)
    }
    repeat(MAX_DAYS_SCANNED) {
        val openedAt = opening.timeInMillis
        if (config.matchesDay(openedAt)) {
            val elapsed = fromEpochMs - openedAt + 1
            val slot = if (elapsed <= 0) 0L else (elapsed + intervalMs - 1) / intervalMs
            if (slot * config.intervalMinutes < window.lengthMinutes) {
                return openedAt + slot * intervalMs
            }
        }
        opening.add(Calendar.DAY_OF_YEAR, 1)
    }
    return null
}
