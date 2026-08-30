package io.github.m1n1m1.easymatic.engine.trigger

import kotlin.math.abs

/**
 * A platform fence this app registered, as much of it as is worth remembering.
 *
 * [spec] is what was asked for, [registeredAtMillis] is when, and [bootedAtMillis]
 * is which boot of the device it happened on — see [GeofenceRegistration.stillStands]
 * for why all three are needed to answer one question.
 */
data class RegisteredFence(
    val spec: String,
    val registeredAtMillis: Long,
    val bootedAtMillis: Long,
)

/**
 * Whether a fence we registered earlier is still the fence we want, so that arming
 * can leave it alone.
 *
 * **Why this exists.** `armGeofence` used to remove and re-add its fence on every
 * arm, and a full re-arm happens on every process start — which is not a rare event:
 * the process is reaped and resurrected all night by periodic workers and alarms, and
 * every restart arms every enabled macro from scratch. Each re-registration resets
 * the fence's state inside Play Services, which re-evaluates against the current
 * location and announces the verdict, and "outside" is announced as an ordinary
 * `GEOFENCE_TRANSITION_EXIT`. So the artefact [GeofenceGate] was written to filter
 * was not weather: it was generated on a timer, by this app, all night.
 *
 * Platform geofences live in the system and **outlive the process**. Re-adding one
 * identical to a fence that is already registered buys nothing at all and costs
 * exactly that bug, so the fix is to stop doing it. The gate stays — doze and OEM
 * location stacks produce spurious transitions without our help — but it is no longer
 * being asked to filter a flood we are producing ourselves.
 *
 * Pure, so it is JVM-tested; the remembering is `GeofenceRegistrationStore`'s.
 */
object GeofenceRegistration {

    /**
     * How long a registration is trusted with nothing to confirm it.
     *
     * Play Services can drop a fence and say nothing: an app or Play Services
     * update, location switched off and on again, or the hundred-fence cap evicting
     * one. Nothing reports any of that, and a fence that is silently gone is a macro
     * that silently never runs — the failure this whole area exists to avoid. So a
     * registration is re-made once a day whatever we think we know, which costs one
     * announcement per fence per day and buys a ceiling on how long a lost fence can
     * stay lost.
     */
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    /**
     * How far the derived boot instant may wobble and still be the same boot.
     *
     * It is derived as wall clock minus uptime, and neither clock ticks in step with
     * the other, so the figure drifts by milliseconds over hours and jumps outright
     * when the wall clock is corrected by NTP. A minute is far wider than the drift
     * and far narrower than any two boots of a phone that has been rebooted.
     */
    const val SAME_BOOT_TOLERANCE_MS = 60_000L

    /**
     * Everything about a fence that would make it a *different* fence.
     *
     * [dwellDelayMs] is folded in only when DWELL is actually watched, mirroring
     * `armGeofence`, which calls `setLoiteringDelay` under the same condition: a
     * fingerprint that noticed a field the registration never sent would re-register
     * for a change the platform cannot see.
     */
    fun fingerprint(
        latitude: Double,
        longitude: Double,
        radiusMeters: Float,
        transitions: Set<GeofenceTransition>,
        dwellDelayMs: Int,
    ): String {
        val watched = transitions.map { it.name }.sorted().joinToString("+")
        val loitering = if (GeofenceTransition.DWELL in transitions) dwellDelayMs.toString() else ""
        return "$latitude|$longitude|$radiusMeters|$watched|$loitering"
    }

    /**
     * Whether [remembered] describes a fence that is still registered and still the
     * one [spec] asks for.
     *
     * Three separate ways to answer no, each with its own reason:
     *
     * - **The spec differs.** A moved place, a new radius, DWELL switched on. There
     *   is a different fence to register, so of course we register it.
     * - **The device rebooted.** Play Services does not restore geofences across a
     *   reboot; the app has to re-register them. Detected from the boot instant
     *   rather than by clearing the store from `BootReceiver`, because that
     *   receiver's own KDoc says restricted OEM builds may never deliver
     *   `BOOT_COMPLETED` — and "the broadcast we needed never came" must not be able
     *   to leave a fence permanently un-registered.
     * - **It is older than [MAX_AGE_MS].** See there.
     *
     * A registration stamped in the future fails too (`age` negative), which is what
     * a wall clock moved backwards looks like; re-registering is the harmless answer
     * to a clock nobody can trust.
     */
    // One early return per reason, so the reasons stay legible and stay countable.
    @Suppress("ReturnCount")
    fun stillStands(
        remembered: RegisteredFence?,
        spec: String,
        bootedAtMillis: Long,
        nowMillis: Long,
    ): Boolean {
        if (remembered == null || remembered.spec != spec) return false
        if (abs(remembered.bootedAtMillis - bootedAtMillis) > SAME_BOOT_TOLERANCE_MS) return false
        val age = nowMillis - remembered.registeredAtMillis
        return age in 0..MAX_AGE_MS
    }
}
