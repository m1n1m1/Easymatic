package io.github.m1n1m1.easymatic.data.calendar

import android.content.ContentUris
import android.net.Uri
import android.provider.CalendarContract

/**
 * The column names and URIs `AndroidCalendars` reads, in one place.
 *
 * Separate from the facade implementation for the reason `HueCommands` is separate from
 * `HueTransport`: what a request *says* and how it is sent fail differently, and a
 * projection index that has drifted one column out of step produces a record full of
 * plausible values rather than an error.
 *
 * Every projection here is paired with an index object, and the indices are what the
 * cursor is read by — `cursor.getString(0)` scattered through a 300-line file is how one
 * of them eventually goes wrong silently.
 */
internal object CalendarQueries {

    /** A calendar the phone knows about. */
    val CALENDAR_PROJECTION = arrayOf(
        CalendarContract.Calendars._ID,
        CalendarContract.Calendars.ACCOUNT_NAME,
        CalendarContract.Calendars.ACCOUNT_TYPE,
        CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
        CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        CalendarContract.Calendars.IS_PRIMARY,
    )

    object Calendar {
        const val ID = 0
        const val ACCOUNT_NAME = 1
        const val ACCOUNT_TYPE = 2
        const val DISPLAY_NAME = 3
        const val ACCESS_LEVEL = 4
        const val IS_PRIMARY = 5
    }

    /**
     * One occurrence, read from the `Instances` view rather than from `Events`.
     *
     * That choice is the whole reason a repeating appointment works: `Events` holds one
     * row for the series and a recurrence rule, where `Instances` expands it. Query
     * `Events` and a weekly stand-up is invisible in every week but the first.
     */
    val INSTANCE_PROJECTION = arrayOf(
        CalendarContract.Instances.EVENT_ID,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END,
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.DESCRIPTION,
        CalendarContract.Instances.EVENT_LOCATION,
        CalendarContract.Instances.ALL_DAY,
        CalendarContract.Instances.CALENDAR_ID,
        CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        // Deliberately no account columns. `Instances` is a join over `Events` and
        // `Calendars` and exposes `CalendarColumns` only — `ACCOUNT_NAME` lives on
        // `SyncColumns`, which it does not implement, so naming it here is a compile
        // error and naming it as a raw string would be a query that throws on some
        // providers and not others. The account half of a `CalendarRef` is taken from
        // the calendar list instead, which is one small query and is exact.
        CalendarContract.Instances.ORGANIZER,
        CalendarContract.Instances.STATUS,
        CalendarContract.Instances.RRULE,
        CalendarContract.Instances.AVAILABILITY,
        CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        CalendarContract.Instances.ORIGINAL_ID,
        CalendarContract.Instances.HAS_ALARM,
    )

    object Instance {
        const val EVENT_ID = 0
        const val BEGIN = 1
        const val END = 2
        const val TITLE = 3
        const val DESCRIPTION = 4
        const val LOCATION = 5
        const val ALL_DAY = 6
        const val CALENDAR_ID = 7
        const val CALENDAR_NAME = 8
        const val ORGANIZER = 9
        const val STATUS = 10
        const val RRULE = 11
        const val AVAILABILITY = 12
        const val SELF_STATUS = 13
        const val ORIGINAL_ID = 14
        const val HAS_ALARM = 15
    }

    /** How many minutes before an appointment each of its reminders fires. */
    val REMINDER_PROJECTION = arrayOf(
        CalendarContract.Reminders.EVENT_ID,
        CalendarContract.Reminders.MINUTES,
    )

    object Reminder {
        const val EVENT_ID = 0
        const val MINUTES = 1
    }

    /**
     * What an edit needs to know about the event row before it can touch it.
     *
     * `ORIGINAL_ID` is the one that is easy to leave out and expensive to: a non-null
     * value means this row is *already* an exception to a series, and inserting a second
     * exception against it does nothing whatsoever — silently. `DTSTART` is here so a
     * patch that changes only the length has something to measure from.
     */
    val EVENT_FACTS_PROJECTION = arrayOf(
        CalendarContract.Events.CALENDAR_ID,
        CalendarContract.Events.ALL_DAY,
        CalendarContract.Events.RRULE,
        CalendarContract.Events.RDATE,
        CalendarContract.Events.ORIGINAL_ID,
        CalendarContract.Events.DTSTART,
        // Exactly one of these two carries the length. A one-off appointment has DTEND
        // and a null DURATION; a repeating one has DURATION and a null DTEND, because
        // "ends at 09:30" is not a fact about a series. Reading only DTEND answers zero
        // for every recurring appointment — see IcalDuration.
        CalendarContract.Events.DTEND,
        CalendarContract.Events.DURATION,
    )

    object EventFactsColumn {
        const val CALENDAR_ID = 0
        const val ALL_DAY = 1
        const val RRULE = 2
        const val RDATE = 3
        const val ORIGINAL_ID = 4
        const val DTSTART = 5
        const val DTEND = 6
        const val DURATION = 7
    }

    /**
     * The access level at which an account may add events to a calendar.
     *
     * Below it the calendar is a subscription — a holiday feed, somebody else's shared
     * diary — which reads perfectly and refuses every write. Checking it up front is what
     * lets a node say "that calendar is read-only" instead of reporting a bare failure
     * from an insert nobody can explain.
     */
    const val WRITABLE_ACCESS_LEVEL = CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR

    /**
     * Only calendars the user has switched on in their calendar app.
     *
     * A hidden calendar is one somebody deliberately stopped looking at, so counting its
     * appointments as "am I busy?" would answer a question nobody asked.
     */
    val VISIBLE_ONLY = "${CalendarContract.Calendars.VISIBLE} = 1"

    /**
     * Cancelled occurrences, excluded from every read.
     *
     * These are not deleted rows: declining an invitation, or deleting one occurrence of
     * a series, leaves a tombstone in the view. Without this clause the appointment a
     * macro just deleted goes on being found.
     */
    val NOT_CANCELLED =
        "(${CalendarContract.Instances.STATUS} IS NULL OR " +
            "${CalendarContract.Instances.STATUS} != ${CalendarContract.Events.STATUS_CANCELED})"

    /** Soonest first, so "the next one" is the first row rather than a sort in Kotlin. */
    val BY_START = "${CalendarContract.Instances.BEGIN} ASC"

    /**
     * The instances URI for one window.
     *
     * The window is part of the **path**, not the selection, which is the provider's own
     * design: it is what tells it how far to expand each recurrence rule, and a query
     * without it cannot be answered at all.
     */
    fun instancesIn(fromEpochMs: Long, toEpochMs: Long): Uri =
        CalendarContract.Instances.CONTENT_URI.buildUpon()
            .also { builder ->
                ContentUris.appendId(builder, fromEpochMs)
                ContentUris.appendId(builder, toEpochMs)
            }
            .build()
}
