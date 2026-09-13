package io.github.m1n1m1.easymatic.feature.grapheditor

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import io.github.m1n1m1.easymatic.feature.sharedAxisX
import io.github.m1n1m1.easymatic.ui.theme.Motion

/**
 * How the editor's main region moves when what fills it changes — the graph, or one
 * of [EditorBottomBar]'s three surfaces.
 *
 * Two motions, because two different things are happening and one motion for both
 * would misdescribe each of them:
 *
 *  - **Leaving or returning to the graph is vertical.** A surface rises over the
 *    canvas and drops back off the bottom, on `EditorOverlay`'s own tween, so
 *    "something has come up over what I was working on" reads the same everywhere
 *    in the editor. Only the moving half is animated: the canvas holds still and is
 *    covered, which is what makes it read as *over* rather than as a swap.
 *  - **Switching between surfaces is horizontal**, following the order of the items
 *    in the bar: tap something to the right of what is open and it comes in from
 *    the right. Nothing is being covered here — you are stepping along a row — and
 *    a surface that rose from the bottom each time would claim otherwise three
 *    times in a row. It is Material's shared axis ([sharedAxisX]), the same call
 *    the home screen's tabs make, so a step sideways feels alike everywhere.
 *
 * The vertical tweens follow Material's rule for things that arrive and leave: a
 * surface rising lands on the decelerate curve, one dropping away leaves on the
 * accelerate curve — the same pair `EditorOverlay` uses, so a surface and an
 * overlay move alike.
 *
 * `targetContentZIndex` is load-bearing in the vertical cases and only there. On the
 * way out the departing panel must stay above the canvas it is uncovering; without
 * it `AnimatedContent` draws the newly added content on top, so the panel would
 * slide down *behind* the canvas and simply vanish instead of sliding away.
 */
fun AnimatedContentTransitionScope<EditorTab?>.surfaceTransition(slidePx: Int): ContentTransform {
    val from = initialState
    val to = targetState
    return when {
        from == null -> ContentTransform(
            targetContentEnter = slideInVertically(
                tween(SURFACE_ENTER_MS, easing = Motion.EmphasizedDecelerate),
            ) { it },
            initialContentExit = ExitTransition.None,
            targetContentZIndex = 1f,
        )
        to == null -> ContentTransform(
            targetContentEnter = EnterTransition.None,
            initialContentExit = slideOutVertically(
                tween(SURFACE_EXIT_MS, easing = Motion.EmphasizedAccelerate),
            ) { it },
            targetContentZIndex = 0f,
        )
        else -> sharedAxisX(rightwards = to.ordinal > from.ordinal, slidePx = slidePx)
    }
}

/** Matched to `EditorOverlay`'s rise and fall, so every surface in the editor moves alike. */
private const val SURFACE_ENTER_MS = Motion.DurationMedium2
private const val SURFACE_EXIT_MS = Motion.DurationMedium1
