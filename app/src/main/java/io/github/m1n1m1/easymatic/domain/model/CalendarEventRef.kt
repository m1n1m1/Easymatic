package io.github.m1n1m1.easymatic.domain.model

/**
 * A durable handle on **one occurrence** of one appointment: which calendar, which
 * event row, and — the field that carries the whole design — when *this* occurrence
 * begins.
 *
 * ```
 * evt:<calendarId>|<eventId>|<instanceStartMs>|<title>
 * ```
 *
 * Stored as **text**, for [MailRef]'s reason: it is an ordinary field of
 * [io.github.m1n1m1.easymatic.domain.model.items.CalendarEvent] rather than a nested struct,
 * so one `action.break` reaches it and it can be wired straight into
 * `action.calendar_update`'s scalar `ref` port.
 *
 * **Why the instance start is in the reference.** A repeating appointment is one row in
 * the provider's `Events` table and many rows in its `Instances` view, and an event id
 * alone therefore names *the series*. A macro that found "the 09:00 stand-up on Tuesday"
 * and asked to delete it would, with an event id alone, delete every stand-up there will
 * ever be — an error nobody recovers from, and one that looks like the macro working
 * until the following week. Carrying the occurrence's own start is what lets
 * `action.calendar_update` offer "Only this appointment" and mean it, by writing an
 * exception row keyed on `ORIGINAL_INSTANCE_TIME`.
 *
 * For a one-off appointment the two readings coincide, so the field is harmless there
 * and the node's scope setting has nothing to choose between.
 *
 * **The instance start is the provider's own value**, in UTC for an all-day event, and
 * it is deliberately *not* run through
 * [EventTimes][io.github.m1n1m1.easymatic.domain.model.EventTimes] on its way in here: it is
 * written back to `ORIGINAL_INSTANCE_TIME`, where the provider expects exactly what it
 * gave out. The shifted, human-facing time is
 * [CalendarEvent.startsAt][io.github.m1n1m1.easymatic.domain.model.items.CalendarEvent.startsAt],
 * which is a different field for a different job.
 *
 * Split with a limit of four, so the title keeps every separator it contains —
 * appointments are called "Standup | Team A" all the time, and the three numeric fields
 * before it cannot contain one.
 */
object CalendarEventRef {

    private const val PREFIX = "evt:"
    private const val SEPARATOR = '|'
    private const val PARTS = 4

    // The title is last and unsplit, which is what lets it contain separators.
    private const val CALENDAR = 0
    private const val EVENT = 1
    private const val START = 2
    private const val TITLE = 3

    /** The spec naming this occurrence. */
    fun format(calendarId: Long, eventId: Long, instanceStartMs: Long, title: String): String =
        "$PREFIX$calendarId$SEPARATOR$eventId$SEPARATOR$instanceStartMs$SEPARATOR$title"

    /**
     * What [spec] names, or null when it names nothing usable.
     *
     * Fails closed, for [MailRef]'s reason and rather more sharply: acting on the wrong
     * appointment is worse than acting on none, and the node prints back what it was
     * handed so a reference that arrived through a text transform can be seen.
     */
    fun parse(spec: String): Parsed? {
        val parts = spec.takeIf { it.startsWith(PREFIX) }
            ?.removePrefix(PREFIX)
            ?.split(SEPARATOR, limit = PARTS)
            ?.takeIf { it.size == PARTS }
            ?: return null
        val calendarId = parts[CALENDAR].toLongOrNull()
        val eventId = parts[EVENT].toLongOrNull()
        val instanceStartMs = parts[START].toLongOrNull()
        return if (calendarId != null && eventId != null && instanceStartMs != null) {
            Parsed(calendarId, eventId, instanceStartMs, parts[TITLE])
        } else {
            null
        }
    }

    data class Parsed(
        val calendarId: Long,
        val eventId: Long,
        /** This occurrence's start, as the provider states it. See the class KDoc. */
        val instanceStartMs: Long,
        /** What it was called when it was read. For display and for the run log. */
        val title: String,
    )
}
