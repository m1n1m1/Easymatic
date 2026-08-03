package com.example.ottomatic.feature.grapheditor

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically

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
 *    times in a row.
 *
 * `targetContentZIndex` is load-bearing in the vertical cases and only there. On the
 * way out the departing panel must stay above the canvas it is uncovering; without
 * it `AnimatedContent` draws the newly added content on top, so the panel would
 * slide down *behind* the canvas and simply vanish instead of sliding away.
 */
fun AnimatedContentTransitionScope<EditorTab?>.surfaceTransition(): ContentTransform {
    val from = initialState
    val to = targetState
    return when {
        from == null -> ContentTransform(
            targetContentEnter = slideInVertically(tween(SURFACE_ENTER_MS)) { it },
            initialContentExit = ExitTransition.None,
            targetContentZIndex = 1f,
        )
        to == null -> ContentTransform(
            targetContentEnter = EnterTransition.None,
            initialContentExit = slideOutVertically(tween(SURFACE_EXIT_MS)) { it },
            targetContentZIndex = 0f,
        )
        else -> {
            val rightwards = to.ordinal > from.ordinal
            ContentTransform(
                targetContentEnter = slideInHorizontally(tween(TAB_SWITCH_MS)) { if (rightwards) it else -it },
                initialContentExit = slideOutHorizontally(tween(TAB_SWITCH_MS)) { if (rightwards) -it else it },
                targetContentZIndex = 1f,
            )
        }
    }
}

/** Matched to `EditorOverlay`'s rise and fall, so every surface in the editor moves alike. */
private const val SURFACE_ENTER_MS = 260
private const val SURFACE_EXIT_MS = 220

/** Quicker than the rise: a step sideways is a smaller claim than a surface arriving. */
private const val TAB_SWITCH_MS = 200
