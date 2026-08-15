package com.example.ottomatic.data.calendar

import android.content.Context
import android.database.Cursor
import android.provider.CalendarContract
import com.example.ottomatic.core.service.CalendarAvailability
import com.example.ottomatic.core.service.CalendarEventRecord
import com.example.ottomatic.core.service.CalendarEventStatus
import com.example.ottomatic.core.service.CalendarInfo
import com.example.ottomatic.core.service.CalendarLimits
import com.example.ottomatic.core.service.CalendarList
import com.example.ottomatic.core.service.CalendarWrite
import com.example.ottomatic.core.service.Calendars
import com.example.ottomatic.core.service.EventDraft
import com.example.ottomatic.core.service.EventListing
import com.example.ottomatic.core.service.EventQuery
import com.example.ottomatic.core.service.EventUpdate
import com.example.ottomatic.domain.model.CalendarBusy
import com.example.ottomatic.domain.model.CalendarEventRef
import com.example.ottomatic.domain.model.CalendarRef
import com.example.ottomatic.domain.model.EventTimes
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [Calendars] over the platform's calendar provider.
 *
 * **Reads go through `Instances`, never `Events`**, and that is the single decision the
 * whole class is built around: `Events` holds one row per *series* plus a recurrence
 * rule, where `Instances` expands it. Query `Events` for "this week's appointments" and a
 * weekly stand-up is invisible in every week but the one it was created in — which looks
 * like the calendar being empty rather than like the wrong table.
 *
 * **Every failure is caught, including the `SecurityException` a missing `READ_CALENDAR`
 * throws.** [Calendars] promises that nothing here throws, and a run must not die because
 * a switch in Settings is off. The permission case is worded distinctly rather than
 * folded into a generic failure, because it is the one the user can actually do something
 * about and the Problems panel is already pointing at it.
 *
 * **All-day appointments are shifted on the way out and on the way in**, through
 * [EventTimes] and nothing else — see that class for why reading the raw millis is the
 * most plausible-looking bug available here.
 *
 * The writing half lives in [CalendarWrites], which is where the four recurrence paths
 * are; this class owns reading and the calendar-resolution rules both halves share.
 */
@Suppress("TooManyFunctions") // A facade's six members plus one mapper per cursor column set.
class AndroidCalendars(context: Context) : Calendars {

    private val appContext = context.applicationContext
    private val resolver get() = appContext.contentResolver
    private val writes = CalendarWrites(appContext) { ref -> resolveCalendarId(ref) }

    override suspend fun calendars(): CalendarList =
        guarded({ CalendarList(error = it) }) { CalendarList(readCalendars(), ok = true) }

    override suspend fun events(query: EventQuery): EventListing =
        guarded({ EventListing(error = it) }) { readEvents(query) }

    override suspend fun add(draft: EventDraft): CalendarWrite =
        guarded({ CalendarWrite(error = it) }) { writes.add(draft) }

    override suspend fun update(request: EventUpdate): CalendarWrite =
        guarded({ CalendarWrite(ref = request.ref, error = it) }) { writes.update(request) }

    /**
     * Every visible calendar, over a window wide enough to catch an appointment that
     * started before now and one whose all-day slot is shifted by a timezone.
     *
     * The window is a day either side rather than a moment, because the provider returns
     * every instance *overlapping* it — so a three-day conference in the middle of which
     * this is asked comes back either way, while a narrower window would drop an all-day
     * appointment whose stored UTC bounds sit outside the local day.
     */
    override suspend fun busyNow(): Boolean? = guarded<Boolean?>({ null }) {
        val now = System.currentTimeMillis()
        val listing = readEvents(
            EventQuery(fromEpochMs = now - DAY_MS, untilEpochMs = now + DAY_MS),
        )
        if (listing.ok) CalendarBusy.busyAt(listing.events, now) else null
    }

    /**
     * The next appointment to *begin*, so one that is already running does not count as
     * the next.
     *
     * All-day appointments do count here, unlike in [busyNow]: "when does my next
     * appointment start" is a question a holiday answers, where "am I busy" is not.
     */
    override suspend fun nextEvent(): CalendarEventRecord? = guarded<CalendarEventRecord?>({ null }) {
        val now = System.currentTimeMillis()
        readEvents(
            EventQuery(
                fromEpochMs = now,
                untilEpochMs = now + CalendarLimits.HORIZON_DAYS * DAY_MS,
                limit = NEXT_SCAN_LIMIT,
            ),
        ).events.firstOrNull { it.startEpochMs > now }
    }

    // ---- reading ------------------------------------------------------------------

    private fun readCalendars(): List<CalendarInfo> = buildList {
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            CalendarQueries.CALENDAR_PROJECTION,
            null,
            null,
            "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) add(cursor.toCalendarInfo())
        }
    }

    @Suppress("ReturnCount") // One early return per way a window can fail to be a window.
    private fun readEvents(query: EventQuery): EventListing {
        val calendarId = when (val filter = resolveFilter(query.calendarSpec)) {
            is CalendarFilter.Unreadable -> return EventListing(error = filter.error)
            is CalendarFilter.Every -> null
            is CalendarFilter.One -> filter.id
        }
        val from = query.fromEpochMs
        val until = clampWindow(from, query.untilEpochMs)
        if (until <= from) return EventListing(ok = true)

        val page = readInstances(query, calendarId, from, until)
        return EventListing(withReminders(page.records), ok = true, truncated = page.truncated)
    }

    private fun readInstances(query: EventQuery, calendarId: Long?, from: Long, until: Long): Page {
        // One small query, so each occurrence can name the calendar it is in as a full
        // `CalendarRef` — which is what lets a found appointment feed straight back into
        // another node's Calendar field. `Instances` cannot supply the account half of
        // that spec; see CalendarQueries.INSTANCE_PROJECTION.
        val refByCalendar = readCalendars().mapNotNull { info ->
            CalendarRef.parse(info.ref)?.let { it.calendarId to info.ref }
        }.toMap()

        // A calendar the user named explicitly is read even when it is switched off in
        // their calendar app: hiding it was a decision about the *display*, and a macro
        // pointing straight at it means it.
        val clauses = buildList {
            if (calendarId != null) {
                add("${CalendarContract.Instances.CALENDAR_ID} = $calendarId")
            } else {
                add(CalendarQueries.VISIBLE_ONLY)
            }
            if (query.liveOnly) add(CalendarQueries.NOT_CANCELLED)
        }

        val wanted = query.limit.coerceIn(1, CalendarLimits.MAX_EVENTS)
        val found = mutableListOf<CalendarEventRecord>()
        var truncated = false
        resolver.query(
            CalendarQueries.instancesIn(from, until),
            CalendarQueries.INSTANCE_PROJECTION,
            clauses.joinToString(" AND "),
            null,
            CalendarQueries.BY_START,
        )?.use { cursor ->
            // One row past the cap is enough to know the cap bit; see
            // EventListing.truncated for why that is reported rather than hidden.
            while (!truncated && cursor.moveToNext()) {
                val record = cursor.toEventRecord(refByCalendar)
                if (!matches(record, query)) continue
                if (found.size == wanted) truncated = true else found += record
            }
        }
        return Page(found, truncated)
    }

    private data class Page(val records: List<CalendarEventRecord>, val truncated: Boolean)

    /** Which calendars a query covers, or why it covers none. */
    private sealed interface CalendarFilter {
        data object Every : CalendarFilter
        data class One(val id: Long) : CalendarFilter
        data class Unreadable(val error: String) : CalendarFilter
    }

    @Suppress("ReturnCount") // Three answers, and each is a return; a `when` here would nest.
    private fun resolveFilter(spec: String): CalendarFilter {
        val named = spec.takeIf { it.isNotBlank() } ?: return CalendarFilter.Every
        val reference = CalendarRef.parse(named)
            ?: return CalendarFilter.Unreadable("Not a calendar reference: \"${named.trim()}\"")
        val id = resolveCalendarId(reference)
            ?: return CalendarFilter.Unreadable(
                "That calendar is not on this phone any more: ${reference.displayName}",
            )
        return CalendarFilter.One(id)
    }

    private fun matches(record: CalendarEventRecord, query: EventQuery): Boolean {
        if (query.liveOnly && !CalendarBusy.isLive(record)) return false
        val text = query.titleContains.trim()
        return text.isEmpty() || record.title.contains(text, ignoreCase = true)
    }

    /**
     * Fills in each appointment's earliest reminder, in one extra query for the whole
     * page rather than one per appointment.
     *
     * Skipped entirely when nothing in the page has an alarm, which the provider already
     * told us in `HAS_ALARM` — the common case, and the reason `trigger.calendar_event`'s
     * reminder mode costs a listing nothing when no appointment has a reminder on it.
     */
    private fun withReminders(records: List<CalendarEventRecord>): List<CalendarEventRecord> {
        val ids = records.filter { it.reminderMinutes == ALARM_PRESENT }
            .mapNotNull { CalendarEventRef.parse(it.ref)?.eventId }
            .distinct()
        if (ids.isEmpty()) return records.map { it.withoutReminderMarker() }

        val earliest = mutableMapOf<Long, Int>()
        resolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            CalendarQueries.REMINDER_PROJECTION,
            "${CalendarContract.Reminders.EVENT_ID} IN (${ids.joinToString(",")})",
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val eventId = cursor.getLong(CalendarQueries.Reminder.EVENT_ID)
                val minutes = cursor.getInt(CalendarQueries.Reminder.MINUTES)
                // The *earliest* reminder, so "when my appointment reminds me" means the
                // first nudge rather than an arbitrary one of several.
                val known = earliest[eventId]
                if (minutes >= 0 && (known == null || minutes > known)) earliest[eventId] = minutes
            }
        }
        return records.map { record ->
            val eventId = CalendarEventRef.parse(record.ref)?.eventId
            record.copy(reminderMinutes = earliest[eventId] ?: EventDraft.NO_REMINDER)
        }
    }

    private fun CalendarEventRecord.withoutReminderMarker() =
        if (reminderMinutes == ALARM_PRESENT) copy(reminderMinutes = EventDraft.NO_REMINDER) else this

    // ---- calendar resolution ------------------------------------------------------

    /**
     * The provider's current id for the calendar [reference] names, or null.
     *
     * Two steps, and the second is the one that matters. The stored id is a *local row
     * id*, and removing and re-adding an account mints new ones — so the fast path also
     * checks that the row it found still belongs to the same account, and falls back to
     * matching on the account and the calendar's own name, which are what the server
     * knows and what survives the local table being rebuilt.
     *
     * Without that check the fast path would eventually resolve to whichever calendar
     * inherited the id — reporting success and writing into somebody else's diary.
     */
    private fun resolveCalendarId(reference: CalendarRef.Parsed): Long? {
        val byId = queryCalendars(
            "${CalendarContract.Calendars._ID} = ?",
            arrayOf(reference.calendarId.toString()),
        ).firstOrNull()
        if (byId != null && byId.belongsTo(reference)) return reference.calendarId

        return queryCalendars(
            "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND " +
                "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} = ?",
            arrayOf(reference.accountName, reference.calendarName),
        ).firstOrNull()?.id
    }

    private fun CalendarRow.belongsTo(reference: CalendarRef.Parsed): Boolean =
        reference.accountName.isBlank() || accountName == reference.accountName

    private fun queryCalendars(selection: String, args: Array<String>): List<CalendarRow> = buildList {
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            CalendarQueries.CALENDAR_PROJECTION,
            selection,
            args,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                add(
                    CalendarRow(
                        id = cursor.getLong(CalendarQueries.Calendar.ID),
                        accountName = cursor.getStringOrEmpty(CalendarQueries.Calendar.ACCOUNT_NAME),
                    ),
                )
            }
        }
    }

    private data class CalendarRow(val id: Long, val accountName: String)

    // ---- cursor mapping -----------------------------------------------------------

    private fun Cursor.toCalendarInfo(): CalendarInfo {
        val accountName = getStringOrEmpty(CalendarQueries.Calendar.ACCOUNT_NAME)
        val accountType = getStringOrEmpty(CalendarQueries.Calendar.ACCOUNT_TYPE)
        val name = getStringOrEmpty(CalendarQueries.Calendar.DISPLAY_NAME)
        val id = getLong(CalendarQueries.Calendar.ID)
        return CalendarInfo(
            ref = CalendarRef.format(accountName, accountType, id, name),
            name = name,
            accountName = accountName,
            writable = getInt(CalendarQueries.Calendar.ACCESS_LEVEL) >= CalendarQueries.WRITABLE_ACCESS_LEVEL,
            primary = getInt(CalendarQueries.Calendar.IS_PRIMARY) == 1,
        )
    }

    private fun Cursor.toEventRecord(refByCalendar: Map<Long, String>): CalendarEventRecord {
        val allDay = getInt(CalendarQueries.Instance.ALL_DAY) == 1
        val eventId = getLong(CalendarQueries.Instance.EVENT_ID)
        val calendarId = getLong(CalendarQueries.Instance.CALENDAR_ID)
        val title = getStringOrEmpty(CalendarQueries.Instance.TITLE)
        val begin = getLong(CalendarQueries.Instance.BEGIN)
        val calendarName = getStringOrEmpty(CalendarQueries.Instance.CALENDAR_NAME)
        return CalendarEventRecord(
            // The reference carries the provider's own instance time, untouched, because
            // it is written straight back to ORIGINAL_INSTANCE_TIME. The shifted, human
            // time is startEpochMs — see CalendarEventRef.
            ref = CalendarEventRef.format(calendarId, eventId, begin, title),
            calendarRef = refByCalendar[calendarId].orEmpty(),
            title = title,
            description = getStringOrEmpty(CalendarQueries.Instance.DESCRIPTION),
            location = getStringOrEmpty(CalendarQueries.Instance.LOCATION),
            calendarName = calendarName,
            startEpochMs = EventTimes.fromProvider(begin, allDay),
            endEpochMs = EventTimes.fromProvider(getLong(CalendarQueries.Instance.END), allDay),
            allDay = allDay,
            organiser = getStringOrEmpty(CalendarQueries.Instance.ORGANIZER),
            availability = availabilityOf(getInt(CalendarQueries.Instance.AVAILABILITY)),
            status = statusOf(getInt(CalendarQueries.Instance.STATUS)),
            declined = getInt(CalendarQueries.Instance.SELF_STATUS) ==
                CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED,
            recurring = getStringOrEmpty(CalendarQueries.Instance.RRULE).isNotBlank(),
            // A non-null ORIGINAL_ID means this row is already an exception to a series,
            // which decides which of two write paths an "only this one" edit takes.
            isException = !isNull(CalendarQueries.Instance.ORIGINAL_ID),
            // A marker, replaced by the real figure in withReminders(). Carried this way
            // so a page with no alarms in it costs no second query at all.
            reminderMinutes = if (getInt(CalendarQueries.Instance.HAS_ALARM) == 1) {
                ALARM_PRESENT
            } else {
                EventDraft.NO_REMINDER
            },
        )
    }

    private fun availabilityOf(value: Int): CalendarAvailability = when (value) {
        CalendarContract.Events.AVAILABILITY_FREE -> CalendarAvailability.FREE
        CalendarContract.Events.AVAILABILITY_TENTATIVE -> CalendarAvailability.TENTATIVE
        else -> CalendarAvailability.BUSY
    }

    private fun statusOf(value: Int): CalendarEventStatus = when (value) {
        CalendarContract.Events.STATUS_CANCELED -> CalendarEventStatus.CANCELLED
        CalendarContract.Events.STATUS_TENTATIVE -> CalendarEventStatus.TENTATIVE
        else -> CalendarEventStatus.CONFIRMED
    }

    private fun Cursor.getStringOrEmpty(index: Int): String = if (isNull(index)) "" else getString(index).orEmpty()

    // ---- failure handling ---------------------------------------------------------

    /**
     * Runs [block] off the main thread and turns every failure into an answer.
     *
     * `CancellationException` is rethrown, on the executor's rule: stopping a run must
     * stop it rather than being logged as a calendar that would not answer.
     */
    private suspend fun <T> guarded(onError: (String) -> T, block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("SwallowedException") e: SecurityException) {
            // Deliberately not the platform's own message. There is exactly one reason
            // this is thrown here and the user can act on it, where "Permission Denial:
            // opening provider CalendarProvider2 …" reads as a crash report.
            onError(NO_ACCESS)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            onError(e.message?.takeIf { it.isNotBlank() } ?: "The calendar provider refused the request")
        }
    }

    private fun clampWindow(from: Long, until: Long): Long =
        until.coerceAtMost(from + CalendarLimits.MAX_WINDOW_DAYS * DAY_MS)

    private companion object {
        const val NO_ACCESS = "Ottomatic does not have calendar access"
        const val DAY_MS = 24L * 60 * 60 * 1000

        /**
         * Stands in [CalendarEventRecord.reminderMinutes] between the instances query and
         * the reminders query, meaning "this appointment has at least one".
         *
         * A sentinel rather than a second field, because it exists for the width of one
         * function and a field would put it on the transport type for ever.
         */
        const val ALARM_PRESENT = -2

        /** How many rows `nextEvent` scans past before giving up on finding one ahead. */
        const val NEXT_SCAN_LIMIT = 50
    }
}
