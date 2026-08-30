package io.github.m1n1m1.easymatic.data.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The call state machine, which is the whole of what is worth testing about the call
 * integration and is deliberately the half with no Android in it.
 *
 * The load-bearing assertions are the three the merge exists for: that a call answered
 * and ended reports a **duration** and that one never picked up reports **nothing but
 * `answered = false`**, that a connect with no ring before it is an **outgoing** call, and
 * that one ringing phone described by both channels at once is still **one** call. The
 * last is the bug this design is arranged around — without it every call trigger fires
 * twice on an ordinary incoming phone call, on any phone whose dialer posts a call
 * notification, which is all of them.
 */
class CallSessionTrackerTest {

    private val tracker = CallSessionTracker()

    private val dialer = "com.android.dialer"

    private fun teams(connected: Boolean, caller: String = "Rita") = ParsedCall(
        packageName = "com.microsoft.teams",
        appName = "Teams",
        caller = caller,
        incoming = !connected,
        connected = connected,
    )

    @Test
    fun `an incoming call rings then connects then ends with a duration`() {
        val ringing = tracker.onTelephony("RINGING", nowMs = 0)
        val active = tracker.onTelephony("OFFHOOK", nowMs = 5_000)
        val ended = tracker.onTelephony("IDLE", nowMs = 96_000)

        assertEquals(CallPhase.RINGING, ringing?.phase)
        assertEquals(CallPhase.ACTIVE, active?.phase)
        assertEquals(CallPhase.ENDED, ended?.phase)
        assertTrue(ended!!.answered)
        // Measured from the moment it was picked up, not from the moment it started
        // ringing: "how long was I on the phone" is not "how long was the phone busy".
        assertEquals(91, ended.durationSeconds)
        assertTrue(ended.incoming)
    }

    /** A missed call is a call that ended having never been answered. */
    @Test
    fun `a call that was never picked up ends answered false with no duration`() {
        tracker.onTelephony("RINGING", nowMs = 0)
        val ended = tracker.onTelephony("IDLE", nowMs = 30_000)

        assertEquals(CallPhase.ENDED, ended?.phase)
        assertFalse(ended!!.answered)
        assertEquals(0, ended.durationSeconds)
    }

    /**
     * The only signal either channel gives for direction, and it is why `incoming` is
     * settled when a session opens and never revisited.
     */
    @Test
    fun `a connect with no ring before it is an outgoing call`() {
        val active = tracker.onTelephony("OFFHOOK", nowMs = 0)
        val ended = tracker.onTelephony("IDLE", nowMs = 10_000)

        assertEquals(CallPhase.ACTIVE, active?.phase)
        assertFalse(active!!.incoming)
        assertFalse(ended!!.incoming)
        assertTrue(ended.answered)
    }

    /** The broadcast spells its states in capitals; every reading of them here is lower. */
    @Test
    fun `telephony states are read whatever case they arrive in`() {
        assertEquals(CallPhase.RINGING, tracker.onTelephony("ringing", nowMs = 0)?.phase)
        assertEquals(CallPhase.ACTIVE, tracker.onTelephony("  OffHook ", nowMs = 1_000)?.phase)
    }

    @Test
    fun `an unintelligible telephony state changes nothing`() {
        tracker.onTelephony("RINGING", nowMs = 0)

        assertNull(tracker.onTelephony("sideways", nowMs = 1_000))
        assertEquals(CallPhase.RINGING, tracker.current()?.phase)
    }

    @Test
    fun `an app call rings connects and ends on its notification`() {
        val ringing = tracker.onCallNotification("k1", teams(connected = false), 0, dialer)
        val active = tracker.onCallNotification("k1", teams(connected = true), 4_000, dialer)
        val ended = tracker.onNotificationGone("k1", 64_000)

        assertEquals(CallPhase.RINGING, ringing?.phase)
        assertEquals("Rita", ringing!!.caller)
        assertEquals("Teams", ringing.appName)
        assertEquals(CallPhase.ACTIVE, active?.phase)
        assertEquals(60, ended!!.durationSeconds)
        assertTrue(ended.answered)
    }

    /**
     * A call app re-posts its ongoing notification whenever the timer ticks. Publishing
     * one of those would fire the macro once a second for the length of the call.
     */
    @Test
    fun `a notification repeating itself publishes nothing`() {
        tracker.onCallNotification("k1", teams(connected = true), 0, dialer)

        assertNull(tracker.onCallNotification("k1", teams(connected = true), 1_000, dialer))
        assertNull(tracker.onCallNotification("k1", teams(connected = true), 2_000, dialer))
    }

    @Test
    fun `a notification that was never a call ends nothing`() {
        assertNull(tracker.onNotificationGone("some-other-notification", 0))
    }

    /**
     * The bug the whole merge exists for: one incoming phone call, described by the
     * telephony broadcast *and* by the dialer's own call notification, is one call.
     */
    @Test
    fun `a dialer notification joins the cellular session rather than opening a second`() {
        val ringing = tracker.onTelephony("RINGING", nowMs = 0, dialerPackage = dialer)
        val fromDialer = tracker.onCallNotification(
            key = "0|com.android.dialer|1|null|10123",
            call = ParsedCall(packageName = dialer, appName = "Phone", caller = "Mum"),
            nowMs = 200,
            dialerPackage = dialer,
        )

        assertEquals(CallPhase.RINGING, ringing?.phase)
        assertNull(fromDialer)
        assertEquals(1, tracker.size())
    }

    /**
     * And the point of joining rather than merely suppressing: telephony will not say who
     * is calling below `READ_CALL_LOG`, and the notification will.
     */
    @Test
    fun `the dialer notification supplies the caller telephony cannot`() {
        tracker.onTelephony("RINGING", nowMs = 0, dialerPackage = dialer)
        tracker.onCallNotification(
            key = "0|com.android.dialer|1|null|10123",
            call = ParsedCall(packageName = dialer, appName = "Phone", caller = "Mum"),
            nowMs = 200,
            dialerPackage = dialer,
        )
        val ended = tracker.onTelephony("IDLE", nowMs = 9_000, dialerPackage = dialer)

        assertEquals("Mum", ended?.caller)
        assertEquals("Phone", ended?.appName)
    }

    /** The race the other way: the notification lands before the broadcast does. */
    @Test
    fun `a dialer notification already open is adopted rather than counted twice`() {
        val first = tracker.onCallNotification(
            key = "0|com.android.dialer|1|null|10123",
            call = ParsedCall(packageName = dialer, appName = "Phone", caller = "Mum"),
            nowMs = 0,
            dialerPackage = dialer,
        )
        val second = tracker.onTelephony("RINGING", nowMs = 100, dialerPackage = dialer)

        assertEquals(CallPhase.RINGING, first?.phase)
        assertNull(second)
        assertEquals(1, tracker.size())

        // And the adopted session is the one telephony now drives, so its end is reported
        // once — by whichever channel gets there first, never by both.
        val ended = tracker.onTelephony("IDLE", nowMs = 5_000, dialerPackage = dialer)
        assertEquals("Mum", ended?.caller)
        assertNull(tracker.onNotificationGone("0|com.android.dialer|1|null|10123", 5_100))
    }

    /** Two calls in two apps stay two calls. */
    @Test
    fun `an app call and a cellular call are separate sessions`() {
        tracker.onTelephony("RINGING", nowMs = 0, dialerPackage = dialer)
        tracker.onCallNotification("k1", teams(connected = true), 100, dialer)

        assertEquals(2, tracker.size())
        // The connected one wins the read: "am I on a call?" is answered by the call
        // somebody is actually on, not by the one still ringing.
        assertEquals(CallPhase.ACTIVE, tracker.current()?.phase)
        assertEquals("Teams", tracker.current()?.appName)
    }

    @Test
    fun `no call at all reads as nothing`() {
        assertNull(tracker.current())
    }

    /**
     * Notification access going away leaves the app calls unclosable — nothing will ever
     * report their notifications gone — so they are dropped rather than left to answer
     * "yes, you are on a call" for ever.
     */
    @Test
    fun `losing notification access forgets the app calls and keeps the cellular one`() {
        tracker.onTelephony("OFFHOOK", nowMs = 0, dialerPackage = dialer)
        tracker.onCallNotification("k1", teams(connected = true), 100, dialer)

        tracker.forgetAppCalls()

        assertEquals(1, tracker.size())
        assertEquals(CallPhase.ACTIVE, tracker.current()?.phase)
        assertEquals("", tracker.current()?.appName)
    }

    /** Each entry is fed by another app's notifications, so the map is bounded. */
    @Test
    fun `the oldest call is evicted once the cap is reached`() {
        val tracker = CallSessionTracker(capacity = 2)
        tracker.onCallNotification("k1", teams(connected = false), 0, dialer)
        tracker.onCallNotification("k2", teams(connected = false), 1, dialer)
        tracker.onCallNotification("k3", teams(connected = false), 2, dialer)

        assertEquals(2, tracker.size())
        assertNull(tracker.onNotificationGone("k1", 3))
    }
}
