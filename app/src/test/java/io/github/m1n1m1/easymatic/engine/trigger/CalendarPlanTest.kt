package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.service.CalendarEventRecord
import io.github.m1n1m1.easymatic.core.service.CalendarEventStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which appointment the calendar trigger arms its next alarm for.
 *
 * The whole policy of `trigger.calendar_event` is here and is testable with no calendar
 * provider anywhere, which is the reason it lives in `engine/` rather than beside the
 * query that feeds it.
 */
class CalendarPlanTest {

    private val now = 1_754_200_000_000L

    @Test
    fun `starts mode fires at the start, minus the lead`() {
        val event = event(id = 1, start = now + 60.m, end = now + 120.m)

        val plan = planNext(spec(CalendarWhen.STARTS, lead = 15), listOf(event), now)!!

        assertEquals(now + 45.m, plan.atEpochMs)
        assertEquals(event.ref, plan.event.ref)
    }

    @Test
    fun `ends mode fires at the end`() {
        val event = event(id = 1, start = now + 60.m, end = now + 120.m)

        assertEquals(now + 120.m, planNext(spec(CalendarWhen.ENDS), listOf(event), now)!!.atEpochMs)
    }

    /** "Ten minutes after my last meeting ends" is a negative lead, not a second field. */
    @Test
    fun `a negative lead fires after the moment rather than before it`() {
        val event = event(id = 1, start = now + 60.m, end = now + 120.m)

        assertEquals(now + 130.m, planNext(spec(CalendarWhen.ENDS, lead = -10), listOf(event), now)!!.atEpochMs)
    }

    @Test
    fun `reminder mode takes its offset from the appointment's own reminder`() {
        val event = event(id = 1, start = now + 60.m, end = now + 120.m, reminderMinutes = 30)

        assertEquals(now + 30.m, planNext(spec(CalendarWhen.REMINDER), listOf(event), now)!!.atEpochMs)
    }

    /**
     * The empty case somebody would otherwise spend an evening on: an appointment with no
     * reminder set is invisible to that mode however soon it is.
     */
    @Test
    fun `reminder mode ignores an appointment with no reminder`() {
        val event = event(id = 1, start = now + 60.m, end = now + 120.m, reminderMinutes = -1)

        assertNull(planNext(spec(CalendarWhen.REMINDER), listOf(event), now))
    }

    @Test
    fun `the soonest moment wins, not the soonest appointment`() {
        val soonButNoLead = event(id = 1, start = now + 50.m, end = now + 80.m, reminderMinutes = 5)
        val laterWithLongLead = event(id = 2, start = now + 90.m, end = now + 100.m, reminderMinutes = 60)

        val plan = planNext(spec(CalendarWhen.REMINDER), listOf(soonButNoLead, laterWithLongLead), now)!!

        assertEquals(now + 30.m, plan.atEpochMs)
        assertEquals(2L, plan.event.ref.substringAfter('|').substringBefore('|').toLong())
    }

    /** An appointment already under way is not something to arm an alarm for. */
    @Test
    fun `a moment that has already passed is not planned for`() {
        val started = event(id = 1, start = now - 10.m, end = now + 10.m)

        assertNull(planNext(spec(CalendarWhen.STARTS), listOf(started), now))
    }

    /**
     * The one that breaks the feature against itself: deleting one occurrence leaves a
     * cancelled tombstone behind, and a planner that armed for it would fire for an
     * appointment this app had just removed.
     */
    @Test
    fun `a cancelled or declined occurrence is skipped`() {
        val cancelled = event(id = 1, start = now + 10.m, end = now + 20.m)
            .copy(status = CalendarEventStatus.CANCELLED)
        val declined = event(id = 2, start = now + 20.m, end = now + 30.m).copy(declined = true)
        val real = event(id = 3, start = now + 40.m, end = now + 50.m)

        val plan = planNext(spec(CalendarWhen.STARTS), listOf(cancelled, declined, real), now)!!

        assertEquals(now + 40.m, plan.atEpochMs)
    }

    /**
     * The filter has to be applied *here* rather than afterwards. Applied afterwards, the
     * trigger would arm for an appointment it then discards, and the one it wanted would
     * never be planned for at all.
     */
    @Test
    fun `the title filter decides which appointment is next`() {
        val other = event(id = 1, start = now + 10.m, end = now + 20.m, title = "Lunch")
        val wanted = event(id = 2, start = now + 40.m, end = now + 50.m, title = "Team standup")

        val plan = planNext(spec(CalendarWhen.STARTS, title = "stand"), listOf(other, wanted), now)!!

        assertEquals(now + 40.m, plan.atEpochMs)
        assertEquals("Team standup", plan.event.title)
    }

    @Test
    fun `an empty diary plans nothing at all`() {
        assertNull(planNext(spec(CalendarWhen.STARTS), emptyList(), now))
    }

    /**
     * The identity a re-plan is de-duplicated on. The same occurrence at two different
     * moments is two firings — which is what a lead being edited between arms produces.
     */
    @Test
    fun `the fired key is the occurrence and the moment together`() {
        val event = event(id = 1, start = now + 60.m, end = now + 120.m)
        val atStart = CalendarOccurrence(now + 60.m, event)
        val earlier = CalendarOccurrence(now + 45.m, event)

        val fired = FiredOccurrences()

        assertTrue(fired.add(atStart.key))
        assertFalse(fired.add(atStart.key))
        assertTrue(fired.add(earlier.key))
    }

    /** Bounded, so an arm that lives for months does not accumulate for ever. */
    @Test
    fun `the fired set forgets its oldest entries`() {
        val fired = FiredOccurrences(capacity = 2)

        assertTrue(fired.add("a"))
        assertTrue(fired.add("b"))
        assertTrue(fired.add("c"))
        // "a" fell out, so it is addable again — which is safe, because the planner can no
        // longer produce a moment that far in the past.
        assertTrue(fired.add("a"))
        assertFalse(fired.add("c"))
    }

    private fun spec(mode: CalendarWhen, lead: Int = 0, title: String = "") =
        CalendarWatchSpec(mode = mode, leadMinutes = lead, titleContains = title)

    private fun event(
        id: Long,
        start: Long,
        end: Long,
        title: String = "Meeting",
        reminderMinutes: Int = -1,
    ) = CalendarEventRecord(
        ref = "evt:7|$id|$start|$title",
        title = title,
        startEpochMs = start,
        endEpochMs = end,
        reminderMinutes = reminderMinutes,
    )

    private val Int.m: Long get() = this * 60L * 1000L
}
