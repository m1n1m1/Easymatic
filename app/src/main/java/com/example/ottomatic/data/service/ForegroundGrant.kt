package com.example.ottomatic.data.service

import java.util.concurrent.atomic.AtomicLong

/**
 * The short window after the user has touched one of this app's notifications, during
 * which Android lets a background app start an Activity.
 *
 * ### Why this exists at all
 *
 * [AndroidSystemServices]'s `canStartActivity` asks two questions — do we hold
 * `SYSTEM_ALERT_WINDOW`, or is a window of ours on screen — and refuses **without
 * trying** if both are no. That is right for a macro firing off a geofence, where a
 * silently-dropped `startActivity` would be reported as a success; it is wrong the
 * moment the user has just tapped a notification, because tapping one is precisely the
 * interaction the platform grants a start allowance for. Without this, `action.notify`'s
 * headline case — tap the notification, open the map — would report `Blocked` on any
 * phone that had not been given the overlay permission for some other reason.
 *
 * ### Why a stamp rather than asking the platform
 *
 * There is nothing to ask. The allowance is state inside the activity manager with no
 * public reader, so the only thing an app can do is remember that the interaction
 * happened. [WINDOW_MS] is deliberately shorter than the platform's own window: the
 * branch that follows a notification tap starts within milliseconds, and a generous
 * guess here would only turn a refusal we can explain into a `startActivity` the
 * system drops in silence.
 *
 * ### What it is not
 *
 * Not a permission and not a promise. Whether the allowance really applies is the
 * platform's business and varies by manufacturer; all this does is stop the app
 * refusing *before* the platform gets a chance to accept. When it does not apply the
 * outcome is the one we had all along — `LaunchOutcome.Blocked`, with a line in the
 * run log — so nothing regresses.
 */
internal object ForegroundGrant {

    /** How long after a notification interaction an Activity start is worth attempting. */
    private const val WINDOW_MS = 10_000L

    private val until = AtomicLong(0)

    /** Records that the user has just interacted with one of this app's notifications. */
    fun stamp(nowMs: Long = System.currentTimeMillis()) {
        until.set(nowMs + WINDOW_MS)
    }

    /** Whether an Activity start is worth attempting on the strength of that interaction. */
    fun active(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs < until.get()

    /** Drops the window. For tests, which would otherwise leak one into the next. */
    fun clear() {
        until.set(0)
    }
}
