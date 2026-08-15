package com.example.ottomatic.core.service

import kotlinx.serialization.Serializable

/**
 * The device's calendars and the appointments in them.
 *
 * Reached from `ExecutionContext` and nothing else, on [Mail]'s and [Files]' reasoning.
 * **Nothing here throws**: every member answers a result carrying an error string, or a
 * null, so a node reports what happened and pulses `out` rather than unwinding a run.
 * That includes the `SecurityException` a missing `READ_CALENDAR` produces, which the
 * implementation catches — a run must not die because a switch in Settings is off.
 *
 * **Plural, like [Variables], [Files], [Contacts], [Prompts] and [Waits]**, and here the
 * plural is also load-bearing rather than only conventional: a phone has several
 * calendars, most nodes act on one of them, and `Calendar` would additionally collide by
 * sight with `java.util.Calendar`, which this codebase already imports in
 * `ScheduleTrigger`.
 *
 * **Four members and two convenience reads.** [update] takes an operation enum rather
 * than there being a separate `delete`, which is [Mail.update] and [MailOp]'s
 * arrangement: the node has one enum, so the facade takes one enum and the node never
 * branches over facade members.
 *
 * **This is an action's facade *and* a value node's**, unlike [Mail], [SmartHome] and
 * [Files], and the argument is `value.ha_state`'s taken one step further. The bar
 * CLAUDE.md actually sets is *is this read cheap, and can it fail* — not *does this
 * concern the network*. A query here is one binder round trip into a local SQLite
 * database that a sync adapter has already filled: no socket, no credential, no DNS, no
 * timeout. It is [Variables]' side of the line rather than [Files]', and the fact that
 * separates it from [Files] is the one that file names itself — *a granted folder may be
 * served by a cloud provider, making a read a network call.* A calendar cannot be.
 *
 * What the pull side still does not get is the *reporting*. [busyNow] and [nextEvent]
 * answer a bare null, so "nothing on" and "could not look" are indistinguishable there;
 * keeping those apart is the action side's job, on the exec wire where the latency shows.
 *
 * **Every member takes a plain `String` spec rather than a parsed type**, because `core`
 * may not import `domain`. Each implementation reads it through
 * `com.example.ottomatic.domain.model.CalendarRef` or `CalendarEventRef` and fails
 * closed — [Files]' rule, which is
 * [TimeOfDay][com.example.ottomatic.domain.model.TimeOfDay]'s rule: one reading of the
 * text, several callers.
 *
 * Implemented by `AndroidCalendars` in `data/calendar/`.
 */
interface Calendars {

    /**
     * Every calendar on the phone, read-only ones included.
     *
     * They are listed rather than filtered out, because a macro that *watches* the
     * company holiday feed is as ordinary as one that writes to a personal calendar.
     * [CalendarInfo.writable] is what the add and update nodes check, so they can say
     * "that calendar is read-only" instead of "no such calendar".
     */
    suspend fun calendars(): CalendarList

    /**
     * The occurrences matching [query], soonest first.
     *
     * Occurrences rather than series: a weekly stand-up inside the window appears once
     * per week, each with its own reference, which is what makes "the next one" something
     * a macro can act on.
     */
    suspend fun events(query: EventQuery): EventListing

    /** Creates an appointment, answering with a reference to it. */
    suspend fun add(draft: EventDraft): CalendarWrite

    /** Edits or deletes one occurrence, or a whole series. */
    suspend fun update(request: EventUpdate): CalendarWrite

    /**
     * Whether an appointment is on **right now**, or null when it cannot be told.
     *
     * Every visible calendar, and deliberately taking no argument: `value.calendar_busy`
     * asks one question that means the same thing wherever it is read, which is what lets
     * it be named as an `action.if` source with no edge drawn to it.
     *
     * All-day appointments do not count as busy — see
     * [CalendarBusy][com.example.ottomatic.domain.model.CalendarBusy], where that
     * judgement lives and is tested.
     */
    suspend fun busyNow(): Boolean?

    /**
     * The next appointment to begin, or null when there is none inside
     * [CalendarLimits.HORIZON_DAYS] or it cannot be told.
     *
     * Times are already shifted out of the provider's UTC for an all-day appointment, so
     * a comparison against `value.now` means what it looks like.
     */
    suspend fun nextEvent(): CalendarEventRecord?
}

/**
 * One calendar on the phone.
 *
 * [ref] is the `CalendarRef` spec, which is what a config field stores and what every
 * other member here takes — so nothing has to translate a display name back into an id,
 * which two calendars called "Work" would break outright. [SmartHomeRef]'s move.
 */
data class CalendarInfo(
    val ref: String,
    val name: String,
    val accountName: String,
    /** False for a subscribed or shared calendar the account may only read. */
    val writable: Boolean = false,
    /** The account's own primary calendar, which is what "my calendar" means. */
    val primary: Boolean = false,
)

/**
 * How an appointment says the user should be treated while it is on.
 *
 * Unlabelled, like [MailOp] and for its reason: the `@Label` annotation lives in `domain`
 * and `core` may not import it. It costs nothing, because `NodeSchema` prettifies an
 * unlabelled enum name and "Busy" / "Free" / "Tentative" is what these already say.
 *
 * `@Serializable` because an `engine/` config class names it as a property type, and both
 * the config form and the item schema are derived from the serialization descriptor.
 */
@Serializable
enum class CalendarAvailability {
    BUSY,
    FREE,
    TENTATIVE,
}

/** Whether an appointment is confirmed, provisional, or a tombstone. */
@Serializable
enum class CalendarEventStatus {
    CONFIRMED,
    TENTATIVE,

    /**
     * Not a deleted row but a tombstone: declining an invitation, or deleting one
     * occurrence of a series, leaves one of these behind. Excluded from every read.
     */
    CANCELLED,
}

/** What `action.calendar_update` does to the appointment it was handed. */
@Serializable
enum class CalendarOp {
    UPDATE,
    DELETE,
}

/**
 * Whether an edit or a deletion reaches one occurrence of a repeating appointment or all
 * of them.
 *
 * [THIS_OCCURRENCE] is the default everywhere it is offered, and that is a safety choice
 * rather than a stylistic one: a macro that deletes "today's stand-up" and takes the
 * whole series with it has destroyed something the user cannot get back — and it looks
 * like the macro working until the following week.
 */
@Serializable
enum class SeriesScope {
    THIS_OCCURRENCE,
    WHOLE_SERIES,
}

/** One occurrence of one appointment, as the facade reports it. */
data class CalendarEventRecord(
    /** The `CalendarEventRef` spec naming this occurrence. */
    val ref: String,
    /** The `CalendarRef` spec of the calendar it is in, so it feeds another node's field. */
    val calendarRef: String = "",
    val title: String = "",
    val description: String = "",
    val location: String = "",
    val calendarName: String = "",
    /** Already shifted out of the provider's UTC for an all-day appointment. */
    val startEpochMs: Long = 0,
    /** Exclusive, and for an all-day appointment midnight on the following day. */
    val endEpochMs: Long = 0,
    val allDay: Boolean = false,
    val organiser: String = "",
    val availability: CalendarAvailability = CalendarAvailability.BUSY,
    val status: CalendarEventStatus = CalendarEventStatus.CONFIRMED,
    /** True when this user answered no to the invitation. */
    val declined: Boolean = false,
    /** True when this is one occurrence of a repeating appointment. */
    val recurring: Boolean = false,
    /**
     * True when this occurrence has already been edited away from its series.
     *
     * Decides which of two write paths an "only this appointment" edit takes, and it is
     * on the transport type for exactly that reason — see `CalendarWrites`. Adding a
     * second exception on top of an existing one does nothing at all, silently.
     */
    val isException: Boolean = false,
    /**
     * Minutes before the start of this appointment's earliest reminder, or
     * [EventDraft.NO_REMINDER] when it has none.
     *
     * Carried because `trigger.calendar_event`'s reminder mode takes its offset from it
     * rather than from a field somebody has to fill in twice.
     */
    val reminderMinutes: Int = EventDraft.NO_REMINDER,
)

/**
 * What to look for.
 *
 * The window is closed at both ends rather than open-ended with a count, because the
 * provider's instances view is queried *by window* — the bounds are what tell it how far
 * to expand each recurrence rule, so a query without them cannot be answered at all.
 */
data class EventQuery(
    /** Blank means every visible calendar. */
    val calendarSpec: String = "",
    val fromEpochMs: Long = 0,
    val untilEpochMs: Long = 0,
    /** Case-insensitive, matched against the title. Blank matches everything. */
    val titleContains: String = "",
    /** Drop cancelled and declined occurrences, which is what almost every caller wants. */
    val liveOnly: Boolean = true,
    val limit: Int = CalendarLimits.MAX_EVENTS,
)

/**
 * A new appointment.
 *
 * **A duration rather than an end time**, and that is not a stylistic call: two absolute
 * fields make `end < start` representable with nothing anywhere to detect it, "a one-hour
 * meeting" is what people actually mean, and whole days are what an all-day appointment
 * has to be expressed in. `ScheduleFire.elapsedMs`' rule — a duration is not a
 * [DateTime][com.example.ottomatic.domain.model.schema.DateTime].
 */
data class EventDraft(
    val calendarSpec: String = "",
    val title: String = "",
    val description: String = "",
    val location: String = "",
    val startEpochMs: Long = 0,
    val durationMinutes: Long = DEFAULT_DURATION_MINUTES,
    val allDay: Boolean = false,
    val availability: CalendarAvailability = CalendarAvailability.BUSY,
    /** Minutes before the start, or [NO_REMINDER] for an appointment with none. */
    val reminderMinutes: Int = NO_REMINDER,
) {
    companion object {
        const val NO_REMINDER: Int = -1
        const val DEFAULT_DURATION_MINUTES: Long = 60
    }
}

/**
 * An edit or a deletion of one appointment.
 *
 * Every field of [patch] is optional and **null means leave it alone**. Null rather than
 * blank, so a description can be *cleared* as well as left alone — two things a single
 * blank string could not tell apart. Turning a form's blank field into a null is the
 * node's job, and the node's KDoc owns the "empty means unchanged" promise to the user.
 */
data class EventUpdate(
    val ref: String,
    val op: CalendarOp = CalendarOp.UPDATE,
    val scope: SeriesScope = SeriesScope.THIS_OCCURRENCE,
    val patch: EventPatch = EventPatch(),
)

/** What to change. Null means leave it alone — see [EventUpdate]. */
data class EventPatch(
    val title: String? = null,
    val description: String? = null,
    val location: String? = null,
    val startEpochMs: Long? = null,
    val durationMinutes: Long? = null,
    val availability: CalendarAvailability? = null,
)

/** Every calendar on the phone, or a failure. */
data class CalendarList(
    val calendars: List<CalendarInfo> = emptyList(),
    val ok: Boolean = false,
    val error: String = "",
)

/**
 * What a query found, or a failure.
 *
 * An empty [events] with a blank [error] is "the window is empty", which is an answer;
 * a non-blank [error] is "I could not look", which is a failure. A list can carry that
 * distinction where a single fact could not — which is why [FileFacts] needed a separate
 * `exists` and this does not.
 *
 * [truncated] is reported rather than the cap being applied in silence: a macro looping
 * over "everything this year" that quietly saw the first few hundred appointments would
 * behave correctly and be wrong. [FileListing]'s reasoning.
 */
data class EventListing(
    val events: List<CalendarEventRecord> = emptyList(),
    val ok: Boolean = false,
    val truncated: Boolean = false,
    val error: String = "",
)

/**
 * The receipt from a write.
 *
 * **`changed = false` with a blank [error] is not a failure** — it is "the calendar was
 * reached and there was nothing to do", which is what deleting an appointment somebody
 * had already removed looks like. That is `LightChanged`'s "the hub was reached and had
 * nothing lit to change", and the consequence is the same: the node logs the blank case
 * at INFO and the other at WARN, because a macro that tidies a calendar every evening
 * should not file a warning on the evenings it was already tidy.
 */
data class CalendarWrite(
    /** The appointment acted on, or the newly created one. */
    val ref: String = "",
    val changed: Boolean = false,
    val error: String = "",
)

/** Bounds a calendar read must respect. */
object CalendarLimits {
    /**
     * The most occurrences one query may answer with.
     *
     * Generous, because the natural windows people ask for are a day, a week or a month
     * and none comes near it — the cap is here for "everything this year on a shared
     * calendar", where the honest answer is a list plus [EventListing.truncated].
     */
    const val MAX_EVENTS: Int = 500

    /** The widest window a query may span, so a mistyped date cannot scan a decade. */
    const val MAX_WINDOW_DAYS: Int = 366

    /**
     * How far ahead [Calendars.nextEvent] and the trigger's planner look.
     *
     * Bounded rather than unbounded because expanding recurrence rules is the expensive
     * part of a calendar query, and "the next appointment" beyond three months is not a
     * question a macro is really asking. A trigger that finds nothing inside it re-checks
     * rather than giving up — see `CalendarEventTrigger`.
     */
    const val HORIZON_DAYS: Int = 90

    /** How long a change watch coalesces provider notifications before reporting one. */
    const val CHANGE_DEBOUNCE_MS: Long = 2_000

    /** How long a planner with nothing in the horizon waits before looking again. */
    const val RECHECK_MINUTES: Long = 6 * 60
}

/**
 * No calendars at all: every read is empty and every write refuses.
 *
 * The engine-only default, so a node under test needs no platform behind it — and the
 * honest answer on a device whose provider is genuinely absent, which happens on
 * stripped-down builds and on watch faces.
 */
object NoCalendars : Calendars {
    override suspend fun calendars(): CalendarList = CalendarList(error = UNAVAILABLE)
    override suspend fun events(query: EventQuery): EventListing = EventListing(error = UNAVAILABLE)
    override suspend fun add(draft: EventDraft): CalendarWrite = CalendarWrite(error = UNAVAILABLE)
    override suspend fun update(request: EventUpdate): CalendarWrite =
        CalendarWrite(ref = request.ref, error = UNAVAILABLE)

    override suspend fun busyNow(): Boolean? = null
    override suspend fun nextEvent(): CalendarEventRecord? = null

    private const val UNAVAILABLE = "Calendar access is not available on this phone"
}
