package io.github.m1n1m1.easymatic.core.trigger

/**
 * The payload keys a [TriggerSource.CALL] event carries.
 *
 * These live in `core` rather than being written out on both sides, which is a departure
 * from how `trigger.fingerprint_gesture` and `trigger.volume_button` do it — those
 * duplicate their contract in `engine` and keep a test to stop the two drifting, because
 * `data` may not import `engine`. Neither may `engine` import `data`, so duplication is
 * the only option *between those two layers*. `core` is beneath both, so a shared
 * declaration is available here and is simply better: nine keys spelled twice is nine
 * chances for a silent empty string, and a test that catches drift is worse than a
 * compiler that makes it impossible.
 *
 * Two producers fill this — `SystemStateReceiver` from the telephony broadcast and
 * `NotificationListener` from a call app's notification — and both go through
 * `CallSessions` first, so what reaches the bus is one merged session either way.
 */
object CallPayload {

    /** `"ringing"`, `"active"` or `"ended"`. */
    const val KEY_STATE = "callState"

    /** Who is on the other end, as the call app printed it. May be empty. */
    const val KEY_CALLER = "caller"

    /** The calling app's own name for itself, e.g. `Teams`. */
    const val KEY_APP_NAME = "appName"

    /** The calling app's package name, which is what an app filter compares. */
    const val KEY_PACKAGE = "package"

    /** `"false"` for a call this phone placed. */
    const val KEY_INCOMING = "incoming"

    /** `"true"` when the call app said this was a video call. */
    const val KEY_VIDEO = "video"

    /** `"true"` when the call was picked up. Meaningful only on [STATE_ENDED]. */
    const val KEY_ANSWERED = "answered"

    /** Seconds spent connected. Meaningful only on [STATE_ENDED]; `0` for a missed call. */
    const val KEY_DURATION_SECONDS = "durationSeconds"

    /** Epoch millis, read back through `TriggerEvent.timestamp`. */
    const val KEY_TIMESTAMP = "timestamp"

    const val STATE_RINGING = "ringing"
    const val STATE_ACTIVE = "active"
    const val STATE_ENDED = "ended"
}
