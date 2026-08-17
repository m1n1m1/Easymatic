package com.example.ottomatic.engine.trigger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the rule that stopped the app generating its own three-in-the-morning exits.
 *
 * The scenario every test here is a slice of: a fence was registered once, and the
 * process has since been killed and restarted — by a periodic worker, an alarm, the
 * user opening the app. The fence itself is still registered, because platform
 * geofences outlive the process. Re-adding it would make Play Services re-derive its
 * state and announce it, and "outside" is announced as an ordinary EXIT.
 */
class GeofenceRegistrationTest {

    private val home = spec()

    private val bootedAt = 1_700_000_000_000L

    private val registeredAt = bootedAt + 60_000L

    private fun spec(
        latitude: Double = 48.2082,
        longitude: Double = 16.3738,
        radiusMeters: Float = 100f,
        transitions: Set<GeofenceTransition> = setOf(GeofenceTransition.ENTER, GeofenceTransition.EXIT),
        dwellDelayMs: Int = 30_000,
    ) = GeofenceRegistration.fingerprint(latitude, longitude, radiusMeters, transitions, dwellDelayMs)

    private fun remembered(
        spec: String = home,
        at: Long = registeredAt,
        boot: Long = bootedAt,
    ) = RegisteredFence(spec, at, boot)

    // ── The fingerprint ──────────────────────────────────────────────────────

    @Test
    fun `the same fence fingerprints the same however the transition set was built`() {
        val forwards = spec(transitions = setOf(GeofenceTransition.ENTER, GeofenceTransition.EXIT))
        val backwards = spec(transitions = setOf(GeofenceTransition.EXIT, GeofenceTransition.ENTER))

        assertEquals("a Set has no order and a fingerprint must not invent one", forwards, backwards)
    }

    @Test
    fun `moving the place changes the fingerprint`() {
        assertNotEquals(home, spec(latitude = 48.3))
    }

    @Test
    fun `resizing the place changes the fingerprint`() {
        assertNotEquals(home, spec(radiusMeters = 200f))
    }

    /**
     * `armGeofence` calls `setLoiteringDelay` only alongside DWELL, so a fingerprint
     * that noticed the field anyway would re-register for a change the platform
     * cannot see — and every re-registration is an announcement.
     */
    @Test
    fun `the dwell delay is ignored on a fence that does not watch for dwell`() {
        assertEquals(home, spec(dwellDelayMs = 90_000))
    }

    @Test
    fun `the dwell delay counts on a fence that does`() {
        val watching = setOf(GeofenceTransition.ENTER, GeofenceTransition.EXIT, GeofenceTransition.DWELL)

        assertNotEquals(
            spec(transitions = watching, dwellDelayMs = 30_000),
            spec(transitions = watching, dwellDelayMs = 90_000),
        )
    }

    // ── Whether the remembered fence still stands ────────────────────────────

    @Test
    fun `an identical fence from the same boot is left alone`() {
        assertTrue(
            GeofenceRegistration.stillStands(remembered(), home, bootedAt, registeredAt + 60_000L),
        )
    }

    @Test
    fun `nothing remembered means nothing is registered`() {
        assertFalse(GeofenceRegistration.stillStands(null, home, bootedAt, registeredAt + 60_000L))
    }

    @Test
    fun `a moved place is a different fence and is registered again`() {
        assertFalse(
            GeofenceRegistration.stillStands(remembered(), spec(latitude = 48.3), bootedAt, registeredAt + 60_000L),
        )
    }

    /** Play Services drops every geofence across a reboot, told or not. */
    @Test
    fun `a fence remembered from before a reboot is registered again`() {
        val laterBoot = bootedAt + 24L * 60 * 60 * 1_000

        assertFalse(GeofenceRegistration.stillStands(remembered(), home, laterBoot, laterBoot + 60_000L))
    }

    /** Wall clock and uptime do not tick in step; the tolerance absorbs the drift. */
    @Test
    fun `a few seconds of clock drift is still the same boot`() {
        val drifted = bootedAt + 30_000L

        assertTrue(GeofenceRegistration.stillStands(remembered(), home, drifted, registeredAt + 60_000L))
    }

    /**
     * The safety net for a fence Play Services dropped without saying so — an app
     * update, location switched off and on, the hundred-fence cap evicting one.
     */
    @Test
    fun `a registration older than a day is made again`() {
        val tomorrow = registeredAt + GeofenceRegistration.MAX_AGE_MS + 1

        assertFalse(GeofenceRegistration.stillStands(remembered(), home, bootedAt, tomorrow))
    }

    @Test
    fun `a registration just under a day old still stands`() {
        val almost = registeredAt + GeofenceRegistration.MAX_AGE_MS - 1

        assertTrue(GeofenceRegistration.stillStands(remembered(), home, bootedAt, almost))
    }

    /** A wall clock moved backwards; re-registering is the harmless answer. */
    @Test
    fun `a registration stamped in the future is not believed`() {
        assertFalse(GeofenceRegistration.stillStands(remembered(), home, bootedAt, registeredAt - 60_000L))
    }
}
