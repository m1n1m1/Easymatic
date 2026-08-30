package io.github.m1n1m1.easymatic.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The one place an all-day appointment is moved between the calendar provider's clock
 * and the phone's.
 *
 * **This exists because getting it wrong is the most plausible-looking bug in the whole
 * calendar integration.** The provider stores an all-day event with `ALL_DAY = 1`, a
 * timezone of `UTC`, and start/end at **midnight UTC** — that is not an accident of the
 * implementation but the format's own rule, because "the 3rd of August" is a date and
 * has no hour to be in a timezone at all. Read those millis as an ordinary instant and
 * every all-day event shifts by the phone's offset: in Vienna, an event "all day on the
 * 3rd" is reported as starting on the 2nd at 23:00 or 22:00. Nothing errors, nothing is
 * logged, and a macro that fires "the morning of a public holiday" fires the evening
 * before.
 *
 * So the rule, stated once and shared by the reader and the writer the way
 * [TimeOfDay] is shared by `trigger.schedule` and its picker: **an all-day event's times
 * are a date, and a date is rendered at local midnight.** A timed event passes through
 * untouched, which is why every function here takes [allDay] rather than being called
 * only on one branch — a caller that has to remember which of two functions to use is a
 * caller that will eventually forget.
 *
 * Pure and JVM-tested (`EventTimesTest`), in `domain` rather than behind the facade, on
 * [WebUrl]'s reasoning: the interesting half is the arithmetic, and the platform half
 * would need a device to exercise it.
 */
object EventTimes {

    /**
     * The timezone an all-day event must be written with.
     *
     * The provider does not merely prefer this — an all-day event stored against a real
     * zone is read back at a shifted time by every other calendar app on the phone.
     */
    const val ALL_DAY_ZONE: String = "UTC"

    /**
     * A time the provider gave us, as an instant on *this* phone's clock.
     *
     * For a timed event that is [epochMs] unchanged. For an all-day event it is local
     * midnight of the date the provider named.
     */
    fun fromProvider(epochMs: Long, allDay: Boolean, zone: ZoneId = ZoneId.systemDefault()): Long =
        if (!allDay) epochMs else dateAt(epochMs, ZoneOffset.UTC).atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * An instant on this phone's clock, as the provider wants it stored.
     *
     * The inverse of [fromProvider]: for an all-day event, the date [epochMs] falls on
     * locally, at midnight UTC.
     */
    fun toProvider(epochMs: Long, allDay: Boolean, zone: ZoneId = ZoneId.systemDefault()): Long =
        if (!allDay) epochMs else dateAt(epochMs, zone).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /**
     * The exclusive end of an all-day event covering [days] days from [startEpochMs].
     *
     * All-day ends are **exclusive** in the provider — an event on the 3rd alone ends at
     * midnight UTC on the 4th — and that convention is kept rather than tidied away,
     * because it is what makes "is an appointment on right now?" a plain `start <= now <
     * end` on both kinds of event. The alternative, reporting the 3rd as both start and
     * end, would make every all-day event zero-length and `value.calendar_busy` answer
     * no on the one day it should answer yes.
     */
    fun allDayEnd(startEpochMs: Long, days: Int, zone: ZoneId = ZoneId.systemDefault()): Long =
        dateAt(startEpochMs, zone)
            .plusDays(days.coerceAtLeast(1).toLong())
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()

    private fun dateAt(epochMs: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
}
