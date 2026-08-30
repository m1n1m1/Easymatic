package io.github.m1n1m1.easymatic.core.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The decay rule behind a widget tile's "Done"/"Failed" flash.
 *
 * Worth its own test because it is the one piece of widget behaviour that can be
 * checked without a launcher, and because getting it wrong is invisible in the
 * moment: a tile that never decays looks perfectly correct for the three seconds
 * anybody is watching it, and wrong only the next time they glance at their home
 * screen.
 */
class RunFeedbackTest {

    private val now = 1_000_000L

    private fun entry(state: RunFeedback.State, atMs: Long) =
        RunFeedback.Entry(state, atMs, "Morning Routine", "Run")

    @Test
    fun `nothing recorded shows nothing`() {
        assertNull(RunFeedback.displayState(null, now))
    }

    @Test
    fun `a fresh outcome is shown`() {
        assertEquals(
            RunFeedback.State.DONE,
            RunFeedback.displayState(entry(RunFeedback.State.DONE, now), now),
        )
        assertEquals(
            RunFeedback.State.FAILED,
            RunFeedback.displayState(entry(RunFeedback.State.FAILED, now), now),
        )
    }

    @Test
    fun `an outcome still inside the settle window is shown`() {
        val entry = entry(RunFeedback.State.DONE, now - RunFeedback.SETTLE_MS)
        assertEquals(RunFeedback.State.DONE, RunFeedback.displayState(entry, now))
    }

    @Test
    fun `an outcome past the settle window decays to idle`() {
        val entry = entry(RunFeedback.State.DONE, now - RunFeedback.SETTLE_MS - 1)
        assertNull(RunFeedback.displayState(entry, now))
    }

    @Test
    fun `a failure decays too`() {
        val entry = entry(RunFeedback.State.FAILED, now - RunFeedback.SETTLE_MS - 1)
        assertNull(RunFeedback.displayState(entry, now))
    }

    /**
     * A run that is genuinely still going keeps saying so, however long it takes.
     *
     * `action.delay` exists, so a macro can legitimately run for minutes. Decaying
     * RUNNING on the same timer as an outcome would make a long macro's tile go
     * quiet halfway through and read as a tap that was dropped.
     */
    @Test
    fun `running never decays`() {
        val ancient = entry(RunFeedback.State.RUNNING, now - RunFeedback.SETTLE_MS * 1_000)
        assertEquals(RunFeedback.State.RUNNING, RunFeedback.displayState(ancient, now))
    }

    @Test
    fun `keys are scoped to their workflow`() {
        // Node ids are unique within a graph, not across the device, so two macros
        // can hold the same node id and must not share a tile's state.
        assertEquals("wf1:n1", RunFeedback.keyOf("wf1", "n1"))
        assertNotEquals(RunFeedback.keyOf("wf1", "n1"), RunFeedback.keyOf("wf2", "n1"))
    }

    @Test
    fun `a target keys itself the same way`() {
        val target = RunFeedback.Target("wf1", "n1", "Morning Routine", "Run")
        assertEquals(RunFeedback.keyOf("wf1", "n1"), target.key)
    }

    /**
     * A run reports against the key its tile reads, and the outcome replaces the
     * RUNNING that preceded it rather than accumulating beside it.
     */
    @Test
    fun `recording a run lands on its own key`() {
        val target = RunFeedback.Target("wf-record", "n1", "Morning Routine", "Run")
        RunFeedback.running(target, now)
        assertEquals(RunFeedback.State.RUNNING, RunFeedback.entries.value[target.key]?.state)

        RunFeedback.finished(target, ok = false, nowMs = now)
        assertEquals(RunFeedback.State.FAILED, RunFeedback.entries.value[target.key]?.state)
        assertEquals(target.key, RunFeedback.keyOf("wf-record", "n1"))
        assertEquals("Morning Routine", RunFeedback.lastRun.value?.macroName)
    }
}
