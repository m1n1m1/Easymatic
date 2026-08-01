package com.example.ottomatic.feature.grapheditor

import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange

/** What a single finger turned out to want, once it committed to something. */
sealed interface PressIntent {

    /** Lifted without crossing the slop. */
    data object Tap : PressIntent

    /** Crossed the slop. [overSlop] is the movement past it, which would otherwise be lost. */
    data class Drag(val overSlop: Offset) : PressIntent

    /** Held still past the long-press timeout without crossing the slop. */
    data object LongPress : PressIntent

    /** Something else claimed the pointer — the two-finger gate, or a port handle. */
    data object PreEmpted : PressIntent
}

/**
 * Waits for a single finger to decide what it is: a tap, a drag, or a hold.
 *
 * This is the one place the long-press-versus-drag race is resolved, and both
 * gesture surfaces use it so they cannot drift apart. The slop wait and the
 * long-press timeout race each other and exactly one wins: crossing the slop
 * returns early and so cancels the pending long press, while holding still lets
 * the timeout cancel the slop wait. Neither can steal from the other, and no
 * `detectTapGestures(onLongPress =)` / `detectDragGestures` pair — which fight
 * over the same pointer — is involved.
 *
 * It also resolves two ambiguities in the raw APIs that are easy to get wrong:
 * `withTimeoutOrNull` cannot tell "timed out" from "the block returned null", and
 * [awaitTouchSlopOrCancellation] returns null both when the finger lifted and when
 * something else consumed the pointer. Whether anything is still on the glass
 * tells those two apart.
 */
suspend fun AwaitPointerEventScope.awaitPressIntent(
    down: PointerInputChange,
    longPressTimeoutMillis: Long,
): PressIntent = withTimeoutOrNull(longPressTimeoutMillis) {
    var overSlop = Offset.Zero
    val dragged = awaitTouchSlopOrCancellation(down.id) { change, over ->
        change.consume()
        overSlop = over
    }
    when {
        dragged != null -> PressIntent.Drag(overSlop)
        currentEvent.changes.none { it.pressed } -> PressIntent.Tap
        else -> PressIntent.PreEmpted
    }
} ?: PressIntent.LongPress
