package io.github.m1n1m1.easymatic.feature.grapheditor

import androidx.compose.ui.res.stringResource
import io.github.m1n1m1.easymatic.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.max

/**
 * A full-screen overlay for the editor's secondary surfaces — the node palette
 * and the node config form.
 *
 * Both are long, scrollable, keyboard-driven surfaces, which is precisely what a
 * bottom sheet is not for: a sheet reserves the downward drag for dismissal, and
 * that is the same physical gesture as scrolling a list back up. Material
 * arbitrates between the two with a nested-scroll connection, but the handoff
 * misfires at scroll boundaries and on flings — the sheet closes when you only
 * meant to scroll. A full-screen window has no competing drag at all, so
 * scrolling is unambiguous.
 *
 * It is a [Dialog] rather than an in-tree overlay so it gets its own window:
 * back dismisses it for free, and touches cannot fall through to the canvas
 * gesture detectors underneath. [action] fills the top bar's trailing slot.
 *
 * It keeps the rise-from-below motion of the sheet it replaces, and its
 * swipe-down-to-dismiss — see [rememberSwipeDownToDismiss] for why that can be
 * had here without the gesture conflict. Because the caller tears the overlay
 * down as soon as [onClose] runs, closing has to be routed through the
 * `dismiss` handed to [content]: that plays the exit animation first and calls
 * [onClose] once it has finished.
 */
@Composable
fun EditorOverlay(
    title: String,
    onClose: () -> Unit,
    action: @Composable (RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    // Starts hidden with the target already visible, so the enter animation
    // runs on first composition rather than snapping into place.
    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
    val dismiss: () -> Unit = { visibleState.targetState = false }
    if (!visibleState.targetState && !visibleState.currentState) {
        LaunchedEffect(Unit) { onClose() }
    }

    val swipeOffset = remember { mutableFloatStateOf(0f) }
    val swipeToDismiss = rememberSwipeDownToDismiss(swipeOffset, dismiss)

    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // Edge-to-edge, matching the host activity, so the overlay's own
            // statusBarsPadding/imePadding are the ones that position content.
            decorFitsSystemWindows = false,
        ),
    ) {
        AnimatedVisibility(
            visibleState = visibleState,
            enter = slideInVertically(animationSpec = tween(OVERLAY_ENTER_MS)) { it },
            exit = slideOutVertically(animationSpec = tween(OVERLAY_EXIT_MS)) { it },
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = swipeOffset.floatValue }
                    .nestedScroll(swipeToDismiss),
                color = EditorColors.canvasBackground,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    OverlayTopBar(title = title, onClose = dismiss, action = action)
                    content(dismiss)
                }
            }
        }
    }
}

private const val OVERLAY_ENTER_MS = 260
private const val OVERLAY_EXIT_MS = 220
private const val SETTLE_MS = 200

/** How far the panel must be pulled down to close on release. */
private val SWIPE_DISMISS_DISTANCE = 140.dp

/** …or how fast it must be flicked down, in px/s, to close short of that. */
private const val SWIPE_DISMISS_VELOCITY = 1200f

/**
 * Swipe down to dismiss, deliberately narrower than the bottom sheet's version
 * so it cannot resurrect the conflict that made us drop the sheet.
 *
 * Two rules keep it out of the content's way:
 *  - it only claims downward drags the scrolling content has already declined
 *    ([onPostScroll], never `onPreScroll` — pre-scroll is what let the sheet
 *    take a drag the list wanted), so it engages only at the top of the scroll;
 *  - it ignores [NestedScrollSource.SideEffect], so a fling that runs to the
 *    top of the list hands nothing over. Only a real finger drag moves it.
 *
 * Upward drags are the one pre-scroll case: a part-swiped panel returns to rest
 * before the content scrolls, so down-then-up reads as a single gesture.
 */
@Composable
private fun rememberSwipeDownToDismiss(
    offset: MutableFloatState,
    onDismiss: () -> Unit,
): NestedScrollConnection {
    val density = LocalDensity.current
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    return remember(density) {
        object : NestedScrollConnection {
            private val dismissDistancePx = with(density) { SWIPE_DISMISS_DISTANCE.toPx() }

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val swiped = offset.floatValue
                val undoingSwipe = source == NestedScrollSource.UserInput &&
                    available.y < 0f && swiped > 0f
                if (!undoingSwipe) return Offset.Zero
                val consumed = max(available.y, -swiped)
                offset.floatValue = swiped + consumed
                return Offset(0f, consumed)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                offset.floatValue += available.y
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (offset.floatValue <= 0f) return Velocity.Zero
                val shouldDismiss = offset.floatValue >= dismissDistancePx ||
                    available.y >= SWIPE_DISMISS_VELOCITY
                if (shouldDismiss) {
                    // The exit animation carries it the rest of the way down
                    // from wherever the finger left it.
                    currentOnDismiss()
                } else {
                    animate(offset.floatValue, 0f, animationSpec = tween(SETTLE_MS)) { value, _ ->
                        offset.floatValue = value
                    }
                }
                return available
            }
        }
    }
}

/** Mirrors `EditorTopBar`, with a close affordance in place of the workflow controls. */
@Composable
private fun OverlayTopBar(
    title: String,
    onClose: () -> Unit,
    action: @Composable (RowScope.() -> Unit)?,
) {
    Surface(color = EditorColors.chrome) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(60.dp)
                .padding(start = 6.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.grapheditor_close),
                    tint = EditorColors.textPrimary,
                )
            }
            Text(
                text = title,
                color = EditorColors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 6.dp),
            )
            action?.invoke(this)
        }
    }
}
