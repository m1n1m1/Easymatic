package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.service.CalendarEventRecord
import io.github.m1n1m1.easymatic.core.service.EventDraft
import io.github.m1n1m1.easymatic.domain.model.CalendarBusy

private const val MS_PER_MINUTE = 60_000L

/**
 * Which appointment `trigger.calendar_event` should arm its next alarm for, and when.
 *
 * Pure, and in `engine/` rather than `data/` on purpose: this is the whole *policy* of the
 * trigger — which occurrences count, where the offset comes from, what counts as "already
 * past" — and it is exactly the half that is worth testing without a calendar provider
 * anywhere. The platform half is one query, which is why `TriggerHost` exposes this as a
 * lookup and keeps the re-arming loop up here beside `ScheduleTrigger.alarmFlow`'s.
 *
 * **Three modes, one mechanism.** It would be tempting to service the reminder mode from
 * `CalendarContract.ACTION_EVENT_REMINDER`, which exists and which the provider really
 * does broadcast — but it is an *implicit* broadcast and is not on Android 8's exemption
 * list, so a manifest receiver for it is never delivered, and a runtime-registered one
 * only works while the process happens to be alive, which is the one moment a trigger must
 * not depend on. So all three modes plan their own exact alarm and differ only in where
 * the moment comes from. The reminder mode reads it off the appointment's own reminder,
 * which additionally makes it work on phones whose OEM calendar has replaced the
 * provider's alert path entirely.
 */
internal fun planNext(
    spec: CalendarWatchSpec,
    events: List<CalendarEventRecord>,
    afterEpochMs: Long,
): CalendarOccurrence? = events
    .asSequence()
    // Cancelled and declined occurrences are tombstones rather than deletions, so without
    // this the trigger would arm an alarm for an appointment deleted through this app's
    // own delete-one path. The feature would break itself.
    .filter { CalendarBusy.isLive(it) }
    .filter { spec.titleContains.isBlank() || it.title.contains(spec.titleContains.trim(), ignoreCase = true) }
    .mapNotNull { event -> spec.momentFor(event)?.let { CalendarOccurrence(it, event) } }
    // Strictly after, so the occurrence whose alarm has just fired is not immediately the
    // next one again. `FiredOccurrences` guards the millisecond either side of that.
    .filter { it.atEpochMs > afterEpochMs }
    .minByOrNull { it.atEpochMs }

/**
 * When this trigger should fire for [event], or null when it should not fire for it at
 * all.
 *
 * Null is only ever the reminder mode's answer, and it is the honest one: an appointment
 * with no reminder on it has no moment for "when its reminder falls due" to mean. The
 * trigger says so once, in the macro's own console, so "why did that not fire" has
 * somewhere to be answered.
 */
private fun CalendarWatchSpec.momentFor(event: CalendarEventRecord): Long? {
    val lead = leadMinutes * MS_PER_MINUTE
    return when (mode) {
        CalendarWhen.STARTS -> event.startEpochMs - lead
        CalendarWhen.ENDS -> event.endEpochMs - lead
        CalendarWhen.REMINDER -> event.reminderMinutes
            .takeIf { it != EventDraft.NO_REMINDER && it >= 0 }
            ?.let { event.startEpochMs - it * MS_PER_MINUTE }
    }
}

/**
 * The occurrences this arm has already fired for.
 *
 * **`MessageDedup`'s problem, not `MailSeenStore`'s**, and the difference decides whether
 * anything goes on disk. A mailbox has no natural boundary between seen and unseen, so its
 * high-water mark must survive process death or the next poll reports everything as new.
 * Here the past is excluded *by construction* — the planner only ever asks for moments
 * after now — so a restart cannot replay something already fired.
 *
 * What does need guarding is one arm's own re-planning: a calendar change two seconds
 * after a fire re-runs the planner, and an occurrence whose alarm has just gone off but
 * whose moment is a millisecond in the future would come back and fire twice. That is
 * "have I already reported *this*", which takes `MessageDedup`'s answer.
 *
 * It is also deliberately not `BatteryLevelWorker`'s hysteresis, which is a third shape: a
 * level hovering at a threshold flaps and needs a latch, where an occurrence is a discrete
 * identity and needs a set of identities.
 *
 * Bounded, because an arm that lives for months would otherwise accumulate one entry per
 * appointment for ever. The oldest entries are the ones furthest in the past, which are
 * exactly the ones the planner can no longer produce.
 */
internal class FiredOccurrences(private val capacity: Int = MAX_REMEMBERED) {

    private val seen = LinkedHashSet<String>()

    /** Records [key], answering false when it was already there. */
    fun add(key: String): Boolean {
        if (!seen.add(key)) return false
        if (seen.size > capacity) seen.iterator().let { it.next(); it.remove() }
        return true
    }

    private companion object {
        const val MAX_REMEMBERED = 64
    }
}
