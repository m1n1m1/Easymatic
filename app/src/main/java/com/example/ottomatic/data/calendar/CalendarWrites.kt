package com.example.ottomatic.data.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import com.example.ottomatic.core.service.CalendarAvailability
import com.example.ottomatic.core.service.CalendarOp
import com.example.ottomatic.core.service.CalendarWrite
import com.example.ottomatic.core.service.EventDraft
import com.example.ottomatic.core.service.EventPatch
import com.example.ottomatic.core.service.EventUpdate
import com.example.ottomatic.core.service.SeriesScope
import com.example.ottomatic.domain.model.CalendarEventRef
import com.example.ottomatic.domain.model.CalendarRef
import com.example.ottomatic.domain.model.EventTimes
import com.example.ottomatic.domain.model.IcalDuration
import java.util.TimeZone
import kotlin.math.ceil

/**
 * The writing half of [AndroidCalendars]: creating, editing and deleting appointments.
 *
 * Split out because writing a calendar has **four paths and three of them are one line
 * away from a silent disaster**, and they deserve to be read together rather than
 * scattered among the readers:
 *
 *  - *delete, whole series* — delete the event row;
 *  - *delete, one occurrence* — insert an exception carrying `STATUS_CANCELED`;
 *  - *edit, whole series* — update the event row;
 *  - *edit, one occurrence* — insert an exception carrying the changed columns.
 *
 * **And a fifth case that looks like none of them**: an occurrence that has *already*
 * been edited is no longer part of its series — its own row carries a non-null
 * `ORIGINAL_ID` — and inserting a second exception against it does nothing at all. That
 * row is edited directly whatever scope was asked for, because for it the two scopes mean
 * the same thing. Missing this reports success and changes nothing.
 *
 * **`ORIGINAL_INSTANCE_TIME` takes the provider's own instance time**, which for an
 * all-day series is the UTC-midnight value rather than the local one a
 * [CalendarEvent][com.example.ottomatic.domain.model.items.CalendarEvent] renders. That
 * is exactly why [CalendarEventRef] carries the raw number and [EventTimes] is applied
 * only on the way to the item; getting it the other way round cancels nothing and reports
 * success.
 */
@Suppress("TooManyFunctions") // Four write paths, plus one small reader or converter each.
internal class CalendarWrites(
    context: Context,
    private val resolveCalendarId: (CalendarRef.Parsed) -> Long?,
) {

    private val appContext = context.applicationContext
    private val resolver get() = appContext.contentResolver

    // One early return per way a draft can be refused before the network — and refusing
    // before the provider is the point, since its own answer names neither the calendar
    // nor the reason.
    @Suppress("ReturnCount")
    fun add(draft: EventDraft): CalendarWrite {
        val reference = CalendarRef.parse(draft.calendarSpec)
            ?: return CalendarWrite(error = calendarProblem(draft.calendarSpec))
        val calendarId = resolveCalendarId(reference)
            ?: return CalendarWrite(error = "That calendar is not on this phone any more: ${reference.displayName}")
        if (!isWritable(calendarId)) {
            return CalendarWrite(error = "\"${reference.displayName}\" is read-only, so nothing can be added to it")
        }
        if (draft.title.isBlank()) return CalendarWrite(error = "An appointment needs a title")

        val start = providerStart(draft.startEpochMs, draft.allDay)
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, draft.title)
            put(CalendarContract.Events.DESCRIPTION, draft.description)
            put(CalendarContract.Events.EVENT_LOCATION, draft.location)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, providerEnd(draft.startEpochMs, draft.durationMinutes, draft.allDay))
            put(CalendarContract.Events.ALL_DAY, if (draft.allDay) 1 else 0)
            put(CalendarContract.Events.AVAILABILITY, availabilityOf(draft.availability))
            // Non-null in the provider, and a missing one is an insert that throws — which
            // reads as "adding appointments does not work" rather than as a missing field.
            // An all-day appointment must additionally be UTC or every other calendar app
            // on the phone renders it on the wrong day.
            put(CalendarContract.Events.EVENT_TIMEZONE, zoneFor(draft.allDay))
        }

        val created = resolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return CalendarWrite(error = "The calendar refused to add that appointment")
        val eventId = ContentUris.parseId(created)
        if (draft.reminderMinutes >= 0) addReminder(eventId, draft.reminderMinutes)
        return CalendarWrite(
            ref = CalendarEventRef.format(calendarId, eventId, start, draft.title),
            changed = true,
        )
    }

    @Suppress("ReturnCount") // One early return per way the appointment cannot be acted on.
    fun update(request: EventUpdate): CalendarWrite {
        val reference = CalendarEventRef.parse(request.ref)
            ?: return CalendarWrite(ref = request.ref, error = eventProblem(request.ref))
        val facts = eventFacts(reference.eventId)
            ?: return CalendarWrite(ref = request.ref, changed = false)
        if (!isWritable(facts.calendarId)) {
            return CalendarWrite(ref = request.ref, error = "That appointment is in a read-only calendar")
        }

        // Two ways to end up editing the row itself rather than excepting from it: there
        // is no series, or this row *is* already the exception.
        val direct = request.scope == SeriesScope.WHOLE_SERIES || !facts.recurring || facts.isException
        return when {
            request.op == CalendarOp.DELETE && direct -> deleteRow(reference, request.ref)
            request.op == CalendarOp.DELETE -> cancelOccurrence(reference, request.ref)
            direct -> updateRow(reference, facts, request.patch, request.ref)
            else -> exceptOccurrence(reference, facts, request.patch, request.ref)
        }
    }

    // ---- the four paths -----------------------------------------------------------

    private fun deleteRow(reference: CalendarEventRef.Parsed, ref: String): CalendarWrite {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, reference.eventId)
        return CalendarWrite(ref = ref, changed = resolver.delete(uri, null, null) > 0)
    }

    private fun cancelOccurrence(reference: CalendarEventRef.Parsed, ref: String): CalendarWrite {
        val values = ContentValues().apply {
            put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, reference.instanceStartMs)
            put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED)
        }
        val created = resolver.insert(exceptionUri(reference.eventId), values)
        return CalendarWrite(ref = ref, changed = created != null)
    }

    private fun updateRow(
        reference: CalendarEventRef.Parsed,
        facts: EventFacts,
        patch: EventPatch,
        ref: String,
    ): CalendarWrite {
        val values = patchValues(patch, facts)
        if (values.size() == 0) return CalendarWrite(ref = ref, changed = false)
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, reference.eventId)
        return CalendarWrite(ref = ref, changed = resolver.update(uri, values, null, null) > 0)
    }

    private fun exceptOccurrence(
        reference: CalendarEventRef.Parsed,
        facts: EventFacts,
        patch: EventPatch,
        ref: String,
    ): CalendarWrite {
        val values = patchValues(patch, facts, occurrenceStartMs = reference.instanceStartMs)
        if (values.size() == 0) return CalendarWrite(ref = ref, changed = false)
        values.put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, reference.instanceStartMs)
        val created = resolver.insert(exceptionUri(reference.eventId), values)
        return CalendarWrite(ref = ref, changed = created != null)
    }

    private fun exceptionUri(eventId: Long) =
        ContentUris.withAppendedId(CalendarContract.Events.CONTENT_EXCEPTION_URI, eventId)

    // ---- shared helpers -----------------------------------------------------------

    /**
     * The columns a patch changes, and nothing else.
     *
     * A null field contributes no column, which is what makes "leave it alone" mean it —
     * and what makes a patch of nothing at all answer `changed = false` with no error,
     * rather than issuing an update that touches no column and having the provider report
     * whatever it likes.
     *
     * A change to either the start or the length rewrites **both** ends, because the
     * provider stores an absolute `DTEND` and moving only the start would silently
     * lengthen or shorten the appointment.
     */
    private fun patchValues(
        patch: EventPatch,
        facts: EventFacts,
        occurrenceStartMs: Long = facts.startEpochMs,
    ): ContentValues = ContentValues().apply {
        patch.title?.let { put(CalendarContract.Events.TITLE, it) }
        patch.description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        patch.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
        patch.availability?.let { put(CalendarContract.Events.AVAILABILITY, availabilityOf(it)) }

        if (patch.startEpochMs != null || patch.durationMinutes != null) {
            val localStart = patch.startEpochMs
                ?: EventTimes.fromProvider(occurrenceStartMs, facts.allDay)
            val minutes = patch.durationMinutes ?: facts.durationMinutes
            put(CalendarContract.Events.DTSTART, providerStart(localStart, facts.allDay))
            put(CalendarContract.Events.DTEND, providerEnd(localStart, minutes, facts.allDay))
            put(CalendarContract.Events.EVENT_TIMEZONE, zoneFor(facts.allDay))
        }
    }

    private fun addReminder(eventId: Long, minutes: Int) {
        val values = ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, minutes)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        }
        resolver.insert(CalendarContract.Reminders.CONTENT_URI, values)
        // The provider does not derive this from the reminder row, and a calendar app that
        // trusts it shows no alert for an appointment that has one.
        resolver.update(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            ContentValues().apply { put(CalendarContract.Events.HAS_ALARM, 1) },
            null,
            null,
        )
    }

    private fun providerStart(localStartMs: Long, allDay: Boolean): Long =
        EventTimes.toProvider(localStartMs, allDay)

    /**
     * The provider's exclusive end.
     *
     * An all-day appointment is measured in **whole days** rather than in minutes — the
     * provider requires its end to be a day boundary, and a 90-minute all-day appointment
     * is not a thing. Anything shorter than a day rounds up to one, which is what "all
     * day" already meant.
     */
    private fun providerEnd(localStartMs: Long, durationMinutes: Long, allDay: Boolean): Long =
        if (allDay) {
            EventTimes.allDayEnd(localStartMs, ceil(durationMinutes / MINUTES_PER_DAY).toInt())
        } else {
            localStartMs + durationMinutes.coerceAtLeast(1) * MS_PER_MINUTE
        }

    private fun zoneFor(allDay: Boolean): String =
        if (allDay) EventTimes.ALL_DAY_ZONE else TimeZone.getDefault().id

    private fun availabilityOf(availability: CalendarAvailability): Int = when (availability) {
        CalendarAvailability.FREE -> CalendarContract.Events.AVAILABILITY_FREE
        CalendarAvailability.TENTATIVE -> CalendarContract.Events.AVAILABILITY_TENTATIVE
        CalendarAvailability.BUSY -> CalendarContract.Events.AVAILABILITY_BUSY
    }

    private fun isWritable(calendarId: Long): Boolean {
        resolver.query(
            ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId),
            CalendarQueries.CALENDAR_PROJECTION,
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getInt(CalendarQueries.Calendar.ACCESS_LEVEL) >=
                    CalendarQueries.WRITABLE_ACCESS_LEVEL
            }
        }
        return false
    }

    @Suppress("ReturnCount") // No cursor, no row, and a row: three answers, three returns.
    private fun eventFacts(eventId: Long): EventFacts? {
        resolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            CalendarQueries.EVENT_FACTS_PROJECTION,
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val rrule = cursor.getString(CalendarQueries.EventFactsColumn.RRULE).orEmpty()
            val rdate = cursor.getString(CalendarQueries.EventFactsColumn.RDATE).orEmpty()
            val start = cursor.getLong(CalendarQueries.EventFactsColumn.DTSTART)
            val end = if (cursor.isNull(CalendarQueries.EventFactsColumn.DTEND)) {
                null
            } else {
                cursor.getLong(CalendarQueries.EventFactsColumn.DTEND)
            }
            return EventFacts(
                calendarId = cursor.getLong(CalendarQueries.EventFactsColumn.CALENDAR_ID),
                allDay = cursor.getInt(CalendarQueries.EventFactsColumn.ALL_DAY) == 1,
                recurring = rrule.isNotBlank() || rdate.isNotBlank(),
                isException = !cursor.isNull(CalendarQueries.EventFactsColumn.ORIGINAL_ID),
                startEpochMs = start,
                // Whichever of the two columns the provider filled, falling back to the
                // standard hour when it filled neither readably — a length is one thing
                // there is nothing sensible to guess about, so IcalDuration answers null
                // rather than inventing one and the default is chosen here, once.
                durationMinutes = end?.takeIf { it > start }?.let { (it - start) / MS_PER_MINUTE }
                    ?: IcalDuration.parseMinutes(cursor.getString(CalendarQueries.EventFactsColumn.DURATION))
                    ?: EventDraft.DEFAULT_DURATION_MINUTES,
            )
        }
        return null
    }

    private fun calendarProblem(spec: String): String = if (spec.isBlank()) {
        "No calendar chosen"
    } else {
        "Not a calendar reference: \"${spec.trim()}\""
    }

    private fun eventProblem(spec: String): String = if (spec.isBlank()) {
        "No appointment wired in"
    } else {
        // Names what it read rather than only that it failed: a reference that arrived
        // through a variable or a text transform is exactly the case where seeing the
        // string is the whole diagnosis.
        "Not an appointment reference: \"${spec.trim()}\""
    }

    /** What the event row says about itself, read once before any write decides anything. */
    private data class EventFacts(
        val calendarId: Long,
        val allDay: Boolean,
        val recurring: Boolean,
        val isException: Boolean,
        val startEpochMs: Long,
        /** How long it currently lasts, however the provider chose to record that. */
        val durationMinutes: Long,
    )

    private companion object {
        const val MS_PER_MINUTE = 60_000L
        const val MINUTES_PER_DAY = 24.0 * 60
    }
}
