package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.service.CalendarEventRecord
import com.example.ottomatic.domain.model.config.Label
import kotlinx.serialization.Serializable

/** Which moment of an appointment `trigger.calendar_event` fires on. */
@Serializable
enum class CalendarWhen {
    @Label("Its reminder falls due")
    REMINDER,

    @Label("It starts")
    STARTS,

    @Label("It ends")
    ENDS,
}

/**
 * What a calendar trigger is watching for, as the planner needs it.
 *
 * [MailWatchSpec]'s shape, with one difference that is easy to get wrong: **[titleContains]
 * is in here**, where `trigger.mail`'s equivalent filters are deliberately left out of its
 * spec and applied per event instead. The reason mail can do that and this cannot is that
 * mail is handed everything that arrives and decides afterwards, whereas this has to
 * *plan* — it computes which appointment is next and arms a single alarm for it. A title
 * filter applied afterwards would arm the alarm for an appointment the node then discards,
 * and the one it actually wanted would never be planned for at all.
 *
 * [calendarSpec] and [mode] are in here for the same reason. Everything a planner must
 * know to pick *which* occurrence is next belongs to the arm; nothing else does.
 */
data class CalendarWatchSpec(
    val mode: CalendarWhen = CalendarWhen.STARTS,
    /** A `CalendarRef` spec, or blank for every calendar. */
    val calendarSpec: String = "",
    /**
     * Minutes before the moment to fire, for [CalendarWhen.STARTS] and
     * [CalendarWhen.ENDS]. Negative fires afterwards, which is how "ten minutes after my
     * last meeting ends" is expressed.
     *
     * Ignored by [CalendarWhen.REMINDER], which takes its offset from the appointment's
     * own reminder instead — see `CalendarEventTrigger`.
     */
    val leadMinutes: Int = 0,
    /** Case-insensitive; blank matches every appointment. */
    val titleContains: String = "",
)

/**
 * One appointment and the moment this trigger should fire for it.
 *
 * [atEpochMs] is separate from anything on [event] because it is *derived* — a start
 * minus a lead, an end, a reminder offset — and a trigger comparing against the event's
 * own start would fire at the wrong time in two of the three modes.
 */
data class CalendarOccurrence(
    val atEpochMs: Long,
    val event: CalendarEventRecord,
) {
    /**
     * What "I have already fired for this" means.
     *
     * The event reference alone is not enough: it names one occurrence, but the same
     * occurrence fires at different moments in different modes, and a node re-planned
     * after a calendar change must not re-fire for the moment it just handled. The moment
     * is therefore part of the identity.
     */
    val key: String get() = "${event.ref}@$atEpochMs"
}
