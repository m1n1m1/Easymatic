package io.github.m1n1m1.easymatic.domain.model

import io.github.m1n1m1.easymatic.core.service.CalendarAvailability
import io.github.m1n1m1.easymatic.core.service.CalendarEventRecord
import io.github.m1n1m1.easymatic.core.service.CalendarEventStatus

/**
 * Which appointments still count, and which of them actually make somebody busy.
 *
 * Two predicates, pure and JVM-tested, shared by the query action, both value nodes and
 * the trigger's planner rather than being re-derived at each — CLAUDE.md's rule that a
 * trigger and its value node *share the reading and classification code*, which is
 * `OrientationDetector.orientationOf`'s arrangement.
 *
 * They are separate because they answer different questions and one of them is a
 * judgement. [isLive] is a fact about the row: does this appointment exist for this user
 * at all? [isBusy] is an opinion about what "busy" means, and it is written down here so
 * that opinion is in one place and can be argued with.
 */
object CalendarBusy {

    /**
     * Whether this occurrence still exists for this user.
     *
     * **The predicate that fails silently if it is missing**, and it matters more than it
     * looks: cancelling one occurrence of a series is implemented as an exception row
     * carrying [CalendarEventStatus.CANCELLED], and declining an invitation leaves the
     * appointment in place with the user's own attendance set to declined. A reader that
     * skipped this check would go on finding the appointment a macro had just deleted
     * through this app's own delete-one path — the feature breaking itself.
     */
    fun isLive(event: CalendarEventRecord): Boolean =
        event.status != CalendarEventStatus.CANCELLED && !event.declined

    /**
     * Whether this occurrence makes the user busy.
     *
     * Two exclusions beyond [isLive], and the second is the judgement:
     *
     * An appointment marked [CalendarAvailability.FREE] is one whose whole point is that
     * it does not block anything — a reminder to take the bins out, a tentative hold.
     *
     * **An all-day appointment does not count**, which is the line worth defending. A
     * subscribed holiday feed, a birthday calendar or a leave tracker puts an all-day
     * entry on most days of the year, and counting those would make `value.calendar_busy`
     * answer yes almost always — which does not make the node *wrong* so much as it makes
     * it useless, and useless in a way that reads as broken. Somebody who wants "is today
     * a public holiday?" is asking a different question and gets it from
     * `action.calendar_query`, where an all-day appointment is returned like any other.
     */
    fun isBusy(event: CalendarEventRecord): Boolean =
        isLive(event) && !event.allDay && event.availability != CalendarAvailability.FREE

    /**
     * Whether [event] is on at [epochMs].
     *
     * Start-inclusive and end-exclusive, so a meeting ending at 10:00 and one beginning at
     * 10:00 do not both count at 10:00 — and so an all-day appointment, whose end is
     * already midnight on the following day (see [EventTimes.allDayEnd]), covers exactly
     * its own days.
     */
    fun isOnAt(event: CalendarEventRecord, epochMs: Long): Boolean =
        epochMs >= event.startEpochMs && epochMs < event.endEpochMs

    /** Whether any of [events] makes the user busy at [epochMs]. */
    fun busyAt(events: List<CalendarEventRecord>, epochMs: Long): Boolean =
        events.any { isBusy(it) && isOnAt(it, epochMs) }
}
