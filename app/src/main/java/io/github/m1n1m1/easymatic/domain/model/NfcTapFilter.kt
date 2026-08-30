package io.github.m1n1m1.easymatic.domain.model

/**
 * Collapses a burst of taps on the same tag into one.
 *
 * Two things produce a burst, and neither is the user asking for two runs. A tag
 * left resting against the phone is re-discovered by some devices as the field
 * cycles, and a person who is not sure the first tap registered taps again — which
 * is precisely the case where the first one *did*.
 *
 * Deliberately keyed on the id rather than being a plain cooldown: tapping the desk
 * tag and then the car tag two seconds apart is two intentions, and a bare
 * "one tap per second" rule would eat the second one.
 *
 * Pure and clock-injected, so the window is testable without waiting for it.
 */
class NfcTapFilter(private val windowMs: Long = DEFAULT_WINDOW_MS) {

    private var lastUid: String? = null
    private var lastAtMs: Long = 0

    /** Whether this tap should be acted on, remembering it if so. */
    fun accept(uid: String, atEpochMs: Long): Boolean {
        if (uid == lastUid && atEpochMs - lastAtMs < windowMs) return false
        lastUid = uid
        lastAtMs = atEpochMs
        return true
    }

    private companion object {
        /**
         * Long enough to swallow a re-discovery and an impatient second tap, short
         * enough that deliberately tapping the same tag twice — off, then on again —
         * still reads as two.
         */
        const val DEFAULT_WINDOW_MS = 1_500L
    }
}
