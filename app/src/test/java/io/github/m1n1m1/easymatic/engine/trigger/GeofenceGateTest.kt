package io.github.m1n1m1.easymatic.engine.trigger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the rule that stopped `trigger.geofence` firing at three in the morning.
 *
 * The scenario every test here is a slice of: a macro watches a 100 m place at
 * work for an exit. The phone spends the night at home, the process is reaped and
 * resurrected by some alarm, the re-arm re-registers the fence, Play Services
 * re-evaluates and announces "outside" as an ordinary EXIT — with a perfectly
 * good fix, from a phone that really is outside. Only the remembered presence
 * tells that apart from walking out of the building.
 */
class GeofenceGateTest {

    private val radius = 100f

    // ── The remembered presence ──────────────────────────────────────────────

    @Test
    fun `an exit while already outside is not a departure`() {
        val verdict = GeofenceGate.judge(
            event = "exit",
            believed = GeofencePresence.OUTSIDE,
            accuracyMeters = 30f,
            distanceMeters = 5_000.0,
            radiusMeters = radius,
            sinceRegisteredMs = null,
        )

        assertTrue("the re-registration artefact must not reach the macro", verdict is GeofenceVerdict.Discard)
        assertEquals("already outside", GeofencePresence.OUTSIDE, verdict.presence)
    }

    @Test
    fun `an exit while inside is a departure`() {
        val verdict = GeofenceGate.judge(
            event = "exit",
            believed = GeofencePresence.INSIDE,
            accuracyMeters = 30f,
            distanceMeters = 400.0,
            radiusMeters = radius,
            sinceRegisteredMs = null,
        )

        assertTrue(verdict is GeofenceVerdict.Accept)
        assertEquals(GeofencePresence.OUTSIDE, verdict.presence)
    }

    /**
     * The user's own rule, and the one this used to get backwards.
     *
     * The first thing a node ever sees is very often not a crossing at all but the
     * announcement that follows its very first registration — and for a phone that
     * is not at the place it watches, that announcement is an exit with a perfect
     * fix kilometres away, which no other rule here can tell from a departure.
     */
    @Test
    fun `a departure from a place we never saw the phone arrive at is refused`() {
        val verdict = GeofenceGate.judge(
            event = "exit",
            believed = GeofencePresence.UNKNOWN,
            accuracyMeters = 30f,
            distanceMeters = 400.0,
            radiusMeters = radius,
            sinceRegisteredMs = null,
        )

        assertTrue(verdict is GeofenceVerdict.Discard)
    }

    /**
     * It records what the fix said rather than keeping UNKNOWN, which is the
     * opposite of what the two evidence guards do. Those doubt the fix; this one
     * believes it completely and doubts only that a departure means anything with no
     * arrival behind it — so the next exit is judged from a true starting point
     * instead of meeting UNKNOWN a second time.
     */
    @Test
    fun `a refused first departure still records where the fix said we were`() {
        val verdict = GeofenceGate.judge(
            event = "exit",
            believed = GeofencePresence.UNKNOWN,
            accuracyMeters = 30f,
            distanceMeters = 400.0,
            radiusMeters = radius,
            sinceRegisteredMs = null,
        )

        assertEquals(GeofencePresence.OUTSIDE, verdict.presence)
    }

    /**
     * The asymmetry is the design: refusing an enter costs *every* departure after
     * it, because a departure is only believed on the strength of a remembered
     * arrival. Refusing an exit costs one trip.
     */
    @Test
    fun `the first arrival a node ever sees is believed`() {
        val verdict = GeofenceGate.judge(
            event = "enter",
            believed = GeofencePresence.UNKNOWN,
            accuracyMeters = 30f,
            distanceMeters = 20.0,
            radiusMeters = radius,
            sinceRegisteredMs = null,
        )

        assertTrue(verdict is GeofenceVerdict.Accept)
        assertEquals(GeofencePresence.INSIDE, verdict.presence)
    }

    // ── The settling window ──────────────────────────────────────────────────

    /**
     * A registration resets the fence's state inside Play Services, which
     * re-evaluates and announces the result — and "outside" is announced as an
     * ordinary EXIT. What tells that apart from walking out is when it arrives.
     */
    @Test
    fun `an exit seconds after a registration is the registration talking`() {
        val verdict = GeofenceGate.judge(
            event = "exit",
            believed = GeofencePresence.INSIDE,
            accuracyMeters = null,
            distanceMeters = null,
            radiusMeters = radius,
            sinceRegisteredMs = 3_000L,
        )

        assertTrue(verdict is GeofenceVerdict.Discard)
        assertEquals("a fix we would not act on teaches nothing", GeofencePresence.INSIDE, verdict.presence)
    }

    /**
     * Narrow on purpose. A real departure moments after arming has to keep working,
     * and Play Services attaches the fix that made it decide you had left — so a
     * fix past the radius outranks the window.
     */
    @Test
    fun `an exit inside the window with a fix past the radius is still a departure`() {
        val verdict = GeofenceGate.judge(
            event = "exit",
            believed = GeofencePresence.INSIDE,
            accuracyMeters = 40f,
            distanceMeters = 130.0,
            radiusMeters = radius,
            sinceRegisteredMs = 3_000L,
        )

        assertTrue(verdict is GeofenceVerdict.Accept)
        assertEquals(GeofencePresence.OUTSIDE, verdict.presence)
    }

    @Test
    fun `an exit long after the last registration is judged on memory alone`() {
        val verdict = GeofenceGate.judge(
            event = "exit",
            believed = GeofencePresence.INSIDE,
            accuracyMeters = null,
            distanceMeters = null,
            radiusMeters = radius,
            sinceRegisteredMs = 6 * 60 * 60 * 1_000L,
        )

        assertTrue("the user chose to keep believing these", verdict is GeofenceVerdict.Accept)
    }

    /** An arrival is never the thing a state reset produces on a sleeping phone. */
    @Test
    fun `an enter seconds after a registration is left alone`() {
        val verdict = GeofenceGate.judge(
            event = "enter",
            believed = GeofencePresence.OUTSIDE,
            accuracyMeters = null,
            distanceMeters = null,
            radiusMeters = radius,
            sinceRegisteredMs = 3_000L,
        )

        assertTrue(verdict is GeofenceVerdict.Accept)
        assertEquals(GeofencePresence.INSIDE, verdict.presence)
    }

    @Test
    fun `an enter while already inside is not an arrival`() {
        val verdict = GeofenceGate.judge(
            event = "enter",
            believed = GeofencePresence.INSIDE,
            accuracyMeters = 30f,
            distanceMeters = 20.0,
            radiusMeters = radius,
            sinceRegisteredMs = null,
        )

        assertTrue(verdict is GeofenceVerdict.Discard)
        assertEquals(GeofencePresence.INSIDE, verdict.presence)
    }

    // ── The accuracy guard ───────────────────────────────────────────────────

    /**
     * The other half of the night-time story: a stationary phone in doze with
     * Wi-Fi scanning throttled falls back to a cell-tower fix kilometres wide,
     * which lands outside a small circle almost every time.
     */
    @Test
    fun `a cell-tower fix cannot report a departure from a small place`() {
        val verdict = GeofenceGate.judge(
            event = "exit",
            believed = GeofencePresence.INSIDE,
            accuracyMeters = 1_800f,
            distanceMeters = 900.0,
            radiusMeters = radius,
            sinceRegisteredMs = null,
        )

        assertTrue(verdict is GeofenceVerdict.Discard)
        assertTrue((verdict as GeofenceVerdict.Discard).reason.contains("1800 m"))
    }

    /** A fix we would not act on is not one to learn from either. */
    @Test
    fun `a fix rejected for accuracy teaches nothing`() {
        val verdict = GeofenceGate.judge(
            event = "exit",
            believed = GeofencePresence.INSIDE,
            accuracyMeters = 1_800f,
            distanceMeters = 900.0,
            radiusMeters = radius,
            sinceRegisteredMs = null,
        )

        assertEquals("the next good fix must be judged against the truth", GeofencePresence.INSIDE, verdict.presence)
    }

    /**
     * The floor exists because the editor allows a 50 m place, and 40 m is the
     * best fix anyone ever gets indoors — without it such a place would reject
     * every transition it was ever sent.
     */
    @Test
    fun `a tight place still believes an ordinary wifi fix`() {
        val verdict = GeofenceGate.judge(
            event = "exit",
            believed = GeofencePresence.INSIDE,
            accuracyMeters = 90f,
            distanceMeters = 300.0,
            radiusMeters = 50f,
            sinceRegisteredMs = null,
        )

        assertTrue(verdict is GeofenceVerdict.Accept)
    }

    @Test
    fun `a wide place believes a correspondingly coarse fix`() {
        val verdict = GeofenceGate.judge(
            event = "exit",
            believed = GeofencePresence.INSIDE,
            accuracyMeters = 300f,
            distanceMeters = 2_000.0,
            radiusMeters = 500f,
            sinceRegisteredMs = null,
        )

        assertTrue("the threshold scales with the circle being judged", verdict is GeofenceVerdict.Accept)
    }

    // ── The contradiction check ──────────────────────────────────────────────

    /**
     * What keeps the remembered presence from wedging: a fix whose whole
     * uncertainty circle falls on one side of the fence outranks anything
     * remembered, so the belief can never drift permanently out of step.
     */
    @Test
    fun `an exit reported from the middle of the place is refused`() {
        val verdict = GeofenceGate.judge(
            event = "exit",
            believed = GeofencePresence.INSIDE,
            accuracyMeters = 20f,
            distanceMeters = 10.0,
            radiusMeters = radius,
            sinceRegisteredMs = null,
        )

        assertTrue(verdict is GeofenceVerdict.Discard)
        assertEquals(GeofencePresence.INSIDE, verdict.presence)
    }

    @Test
    fun `an enter reported from far outside the place is refused`() {
        val verdict = GeofenceGate.judge(
            event = "enter",
            believed = GeofencePresence.OUTSIDE,
            accuracyMeters = 20f,
            distanceMeters = 4_000.0,
            radiusMeters = radius,
            sinceRegisteredMs = null,
        )

        assertTrue(verdict is GeofenceVerdict.Discard)
    }

    /** Straddling the boundary is what arriving looks like, so it must pass. */
    @Test
    fun `a fix straddling the edge is left to the transition to explain`() {
        val verdict = GeofenceGate.judge(
            event = "enter",
            believed = GeofencePresence.OUTSIDE,
            accuracyMeters = 60f,
            distanceMeters = 120.0,
            radiusMeters = radius,
            sinceRegisteredMs = null,
        )

        assertTrue(verdict is GeofenceVerdict.Accept)
        assertEquals(GeofencePresence.INSIDE, verdict.presence)
    }

    // ── The two that bypass the gate ─────────────────────────────────────────

    /**
     * The away alarm is ours rather than the platform's and carries no location
     * at all, so there is nothing to judge — and nothing it can teach about where
     * the phone is either.
     */
    @Test
    fun `the away alarm passes with no location and changes no belief`() {
        val verdict = GeofenceGate.judge(
            event = "away",
            believed = GeofencePresence.OUTSIDE,
            accuracyMeters = null,
            distanceMeters = null,
            radiusMeters = radius,
            sinceRegisteredMs = null,
        )

        assertTrue(verdict is GeofenceVerdict.Accept)
        assertEquals(GeofencePresence.OUTSIDE, verdict.presence)
    }

    /** Dwelling is *defined* as still being where we already were. */
    @Test
    fun `a dwell is not suppressed as a repeat of the enter before it`() {
        val verdict = GeofenceGate.judge(
            event = "dwell",
            believed = GeofencePresence.INSIDE,
            accuracyMeters = 2_000f,
            distanceMeters = 40.0,
            radiusMeters = radius,
            sinceRegisteredMs = null,
        )

        assertTrue("a dwell waited out the whole loitering delay to say this", verdict is GeofenceVerdict.Accept)
        assertEquals(GeofencePresence.INSIDE, verdict.presence)
    }

    @Test
    fun `a transition carrying no location is judged on memory alone`() {
        val verdict = GeofenceGate.judge(
            event = "exit",
            believed = GeofencePresence.OUTSIDE,
            accuracyMeters = null,
            distanceMeters = null,
            radiusMeters = radius,
            sinceRegisteredMs = null,
        )

        assertTrue("a missing fix is not a perfect one", verdict is GeofenceVerdict.Discard)
    }
}
