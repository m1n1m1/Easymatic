package io.github.m1n1m1.easymatic.domain.model

/**
 * How long an appointment lasts, read from the calendar provider's `DURATION` column.
 *
 * **This exists because a repeating appointment has no end time.** A one-off event stores
 * `DTSTART` and `DTEND`; a repeating one stores `DTSTART`, a recurrence rule and a
 * *`DURATION`* — and leaves `DTEND` null, because "ends at 09:30" is not a fact about a
 * series that occurs every week. So an edit that moves one occurrence half an hour later
 * has to know the length from somewhere, and the only place it is written down is this
 * string. Reading `DTEND` instead answers zero for exactly the appointments an occurrence
 * edit is about, and the appointment silently becomes an hour long, or nothing long.
 *
 * The format is RFC 5545's: `P` followed by either a number of weeks, or days and a `T`
 * section of hours, minutes and seconds — `PT1H`, `PT90M`, `P1D`, `P1DT2H30M`, `P2W`. A
 * leading sign is legal and meaningless here, since an appointment cannot last a negative
 * time, so it is accepted and ignored rather than making the whole string unreadable.
 *
 * Pure and JVM-tested, in `domain` on [WebUrl]'s reasoning: the interesting half is the
 * parsing, and the platform half would need a device to exercise it.
 */
object IcalDuration {

    /**
     * How many minutes [text] describes, or null when it describes nothing usable.
     *
     * Seconds are **rounded up to a whole minute** rather than truncated, so a `PT90S`
     * appointment lasts two minutes rather than one. Rounding down would let an
     * appointment shorter than a minute round to zero, which the provider reads as an end
     * before its own start.
     *
     * Null on anything malformed, which the caller answers with its own default — for
     * [WebUrl]'s reason inverted: there is nothing here worth guessing at, and a guess
     * would resize somebody's appointment.
     */
    @Suppress("ReturnCount") // Three shapes of "unreadable", each answered where it is found.
    fun parseMinutes(text: String?): Long? {
        val body = bodyOf(text) ?: return null
        val at = body.indexOf('T')
        val datePart = if (at < 0) body else body.substring(0, at)
        val timePart = if (at < 0) "" else body.substring(at + 1)
        // A `T` with nothing after it is malformed rather than "no time part", and a unit
        // this format does not have means the string is something else entirely.
        if (at >= 0 && timePart.isEmpty()) return null
        if (leftovers(datePart, "WD") || leftovers(timePart, "HMS")) return null
        return totalMinutes(datePart, timePart)?.takeIf { it > 0 }
    }

    /** Everything after the `P`, or null when there is no readable `P` to be after. */
    private fun bodyOf(text: String?): String? = text
        ?.trim()
        ?.removePrefix("+")
        ?.removePrefix("-")
        ?.uppercase()
        ?.takeIf { it.startsWith('P') }
        ?.substring(1)
        ?.takeIf { it.isNotEmpty() }

    /**
     * The two halves added up, or null when any unit is present but unreadable.
     *
     * Seconds round **up**, so a `PT90S` appointment lasts two minutes rather than one.
     * Rounding down would let anything under a minute become zero, which the provider
     * reads as an end before its own start.
     */
    private fun totalMinutes(datePart: String, timePart: String): Long? {
        // Read as one list so a single unreadable unit fails the whole string, which is
        // what `PT1HxM` has to do: half a duration is not a shorter duration.
        val weighted = listOf(
            unit(datePart, 'W') to DAYS_PER_WEEK * MINUTES_PER_DAY,
            unit(datePart, 'D') to MINUTES_PER_DAY,
            unit(timePart, 'H') to MINUTES_PER_HOUR,
            unit(timePart, 'M') to 1L,
        )
        // Seconds are added separately rather than weighted, because they are the one
        // unit that rounds rather than scaling.
        val seconds = unit(timePart, 'S')
        return if (seconds == null || weighted.any { it.first == null }) {
            null
        } else {
            weighted.sumOf { (value, weight) -> checkNotNull(value) * weight } +
                (seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE
        }
    }

    /**
     * The number attached to [suffix] in [part], zero when it is absent, or null when it
     * is there but is not a number — `PTxH` must fail rather than being read as `PT0H`.
     */
    private fun unit(part: String, suffix: Char): Long? {
        val at = part.indexOf(suffix)
        if (at < 0) return 0
        return part.substring(0, at).takeLastWhile { it.isDigit() }.toLongOrNull()
    }

    /** True when [part] holds anything other than digits and the units it may carry. */
    private fun leftovers(part: String, units: String): Boolean =
        part.any { !it.isDigit() && it !in units }

    private const val DAYS_PER_WEEK = 7L
    private const val MINUTES_PER_DAY = 24L * 60
    private const val MINUTES_PER_HOUR = 60L
    private const val SECONDS_PER_MINUTE = 60L
}
