package io.github.m1n1m1.easymatic.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * Material 3's motion tokens — the easing curves and the duration scale — so that
 * every animation in the app is built from the same handful of values.
 *
 * Material's rule is one sentence: **things arriving decelerate, things leaving
 * accelerate, and things that stay on screen and move use the symmetric curve.** An
 * element sliding in should land softly (`EmphasizedDecelerate`); one sliding off
 * should get out of the way quickly and not linger (`EmphasizedAccelerate`); a tab
 * stepping sideways or a screen doing a parallax under another one is neither
 * (`Emphasized`, `Standard`). The `Emphasized` set is for the motion a user is meant
 * to notice — a screen change, a surface rising — and the `Standard` set for the
 * small, utilitarian moves that should not draw the eye.
 *
 * `Emphasized` itself is a path curve in the spec, which Compose cannot express as
 * one bezier; the `(0.2, 0, 0, 1)` approximation here is the one Material's own
 * Compose components use.
 *
 * Durations follow the same idea: the larger the area that moves, the longer it
 * takes, from [DurationShort2] for a chevron to [DurationLong1] for a whole screen.
 */
object Motion {
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val StandardDecelerate: Easing = CubicBezierEasing(0f, 0f, 0f, 1f)
    val StandardAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    const val DurationShort2 = 100
    const val DurationShort3 = 150
    const val DurationShort4 = 200
    const val DurationMedium1 = 250
    const val DurationMedium2 = 300
    const val DurationMedium3 = 350
    const val DurationMedium4 = 400
    const val DurationLong1 = 450
}
