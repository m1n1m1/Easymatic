package io.github.m1n1m1.easymatic.feature

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.m1n1m1.easymatic.ui.theme.Motion

/**
 * The two Material 3 transition patterns this app moves between screens with.
 *
 * ### Forward and backward
 *
 * `MainActivity`'s `NavHost` uses [ScreenTransitions.forwardEnter] and its three
 * siblings for every push and pop. It is Material's *forward and backward* pattern:
 * the new screen slides in from the right edge over the old one, which does not
 * simply stand still underneath — it drifts a quarter of the way out to the left and
 * dims, so the two move as a stack rather than as a card sliding over a photograph.
 * Back is the mirror image, with the old screen drifting back in from where it went.
 *
 * **The back button and the back gesture play the same animation.** That is not
 * automatic: `NavHost` takes a separate transition pair for a *predictive* pop and
 * defaults it to a shrink-and-fade, so `MainActivity` hands it [backEnter] and
 * [backExit] as well, and the gesture then *scrubs* the same pop the arrow plays.
 * Two choices here exist only to keep that true under a finger, where every frame
 * is held up for inspection. The pop uses the symmetric [Motion.Emphasized], not the
 * accelerate curve Material gives to things leaving: mapped onto a swipe's progress,
 * an accelerate curve makes the screen barely move for most of the gesture and then
 * fly. And the screen underneath dims to [DIM_ALPHA] rather than to nothing, because
 * a tapped back is over in a third of a second while a scrubbed one can sit at a
 * quarter of the way for as long as the finger does — and at a quarter of a fade
 * from zero, home was a black void the editor was being dragged over.
 *
 * ### Shared axis
 *
 * [sharedAxisX] is Material's *shared axis* pattern on the horizontal axis, for
 * content that is *peers* rather than parent and child: the home screen's two tabs
 * and the editor's three surfaces, which the bar below lists side by side. The
 * outgoing content fades out over the first third while both slide a short distance
 * in the direction of travel, and the incoming content fades in over the rest. The
 * slide is deliberately small ([SHARED_AXIS_SLIDE]) — it says *which way* you
 * stepped, where a full-width slide would say you had left for somewhere else, which
 * is the forward pattern's claim and not this one's.
 */
object ScreenTransitions {
    private const val SCREEN_MS = Motion.DurationMedium4

    /** How far the screen underneath drifts, as a fraction of the width: a parallax, not a slide. */
    private const val PARALLAX_DIVISOR = 4

    /** How far the screen underneath dims: present and clearly behind, never gone. */
    private const val DIM_ALPHA = 0.55f

    val forwardEnter: EnterTransition =
        slideInHorizontally(tween(SCREEN_MS, easing = Motion.EmphasizedDecelerate)) { it } +
            fadeIn(tween(Motion.DurationShort2, easing = Motion.StandardDecelerate))

    val forwardExit: ExitTransition =
        slideOutHorizontally(tween(SCREEN_MS, easing = Motion.EmphasizedDecelerate)) { -it / PARALLAX_DIVISOR } +
            fadeOut(tween(SCREEN_MS, easing = Motion.EmphasizedDecelerate), targetAlpha = DIM_ALPHA)

    val backEnter: EnterTransition =
        slideInHorizontally(tween(SCREEN_MS, easing = Motion.Emphasized)) { -it / PARALLAX_DIVISOR } +
            fadeIn(tween(SCREEN_MS, easing = Motion.Emphasized), initialAlpha = DIM_ALPHA)

    val backExit: ExitTransition =
        slideOutHorizontally(tween(SCREEN_MS, easing = Motion.Emphasized)) { it }
}

/** The distance shared-axis content travels: Material's 30 dp. */
private val SHARED_AXIS_SLIDE: Dp = 30.dp

/**
 * [SHARED_AXIS_SLIDE] in pixels, for handing to [sharedAxisX] from a transition
 * spec — which cannot read the density for itself, because it is not composable.
 */
@Composable
fun sharedAxisSlidePx(): Int = with(LocalDensity.current) { SHARED_AXIS_SLIDE.roundToPx() }

/**
 * Material's shared-axis transition along X. [rightwards] is the direction of
 * travel — the incoming content enters from the right — and [slidePx] is
 * [sharedAxisSlidePx], read in composition and captured by the transition spec.
 */
fun sharedAxisX(rightwards: Boolean, slidePx: Int): ContentTransform {
    val offset = if (rightwards) slidePx else -slidePx
    return ContentTransform(
        targetContentEnter = slideInHorizontally(
            tween(SHARED_AXIS_MS, easing = Motion.Standard),
        ) { offset } + fadeIn(
            tween(
                durationMillis = SHARED_AXIS_MS - SHARED_AXIS_FADE_OUT_MS,
                delayMillis = SHARED_AXIS_FADE_OUT_MS,
                easing = Motion.StandardDecelerate,
            ),
        ),
        initialContentExit = slideOutHorizontally(
            tween(SHARED_AXIS_MS, easing = Motion.Standard),
        ) { -offset } + fadeOut(
            tween(SHARED_AXIS_FADE_OUT_MS, easing = Motion.StandardAccelerate),
        ),
    )
}

private const val SHARED_AXIS_MS = Motion.DurationMedium2

/** The outgoing content is gone by the time the incoming one starts to show. */
private const val SHARED_AXIS_FADE_OUT_MS = 90
