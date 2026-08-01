package com.example.ottomatic.feature.grapheditor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange

/**
 * One finger on a node card: tap to select, hold to enter multi-select, drag to
 * move.
 *
 * Replaces the `detectTapGestures` + `detectDragGestures` pair the card used to
 * carry. Two things that pair could not express, and this can:
 *
 *  - **A press is not a selection.** [NodeGestureHandlers.onPress] fires when the
 *    finger lands and is required to change nothing visible, so a pinch that
 *    happens to start on a node never flashes a selection before the two-finger
 *    gate takes it over. The old code selected in `onDragStart`, which is why
 *    zooming used to pick nodes up.
 *  - **A hold means something.** The long press and the drag race each other
 *    inside [awaitPressIntent] rather than living in two detectors that fight over
 *    the same pointer.
 *
 * `requireUnconsumed = true` keeps this off a press that a **port handle** already
 * claimed — the handles are drawn over the card and consume their own down —
 * while consuming the down here keeps the canvas from panning underneath.
 *
 * Deltas are divided by [PointerInputScope.density] but not by the zoom: this
 * modifier sits inside `NodeLayer`'s `graphicsLayer`, so movement arrives already
 * in pre-scale units. The canvas gate above that layer works in screen px instead.
 */
suspend fun PointerInputScope.detectNodeGestures(
    handlers: NodeGestureHandlers,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = true)
    down.consume()
    handlers.onPress()
    when (val intent = awaitPressIntent(down, viewConfiguration.longPressTimeoutMillis)) {
        is PressIntent.Tap -> {
            handlers.onTap()
            // Closes the undo point [onPress] opened. Without this a tap leaves it
            // armed, and the *next* thing to cancel a gesture — a pinch, say —
            // would roll the selection back to before the tap.
            handlers.onFinish()
        }

        is PressIntent.Drag -> {
            handlers.onDragStart()
            handlers.onDrag(intent.overSlop / density)
            dragUntilDone(down.id, handlers)
        }

        is PressIntent.LongPress -> {
            handlers.onLongPress()
            // Fall through into the drag loop rather than ending the gesture, so
            // hold-then-drag enlarges the selection and moves it in one motion
            // instead of demanding the finger be lifted and put back down.
            handlers.onDragStart()
            dragUntilDone(down.id, handlers)
        }

        // Something else claimed the pointer before it committed. The press is
        // still open, so it has to be closed out — otherwise the undo point stays
        // armed and a later cancel reverts a gesture that already ended.
        is PressIntent.PreEmpted -> handlers.onCancel()
    }
}

private suspend fun AwaitPointerEventScope.dragUntilDone(
    pointerId: PointerId,
    handlers: NodeGestureHandlers,
) {
    val completed = drag(pointerId) { change ->
        // Read the delta BEFORE consuming. `positionChange()` returns Offset.Zero
        // for a consumed change, so consuming first reports every frame as no
        // movement and the node never leaves the spot it was grabbed at.
        val delta = change.positionChange()
        change.consume()
        handlers.onDrag(delta / density)
    }
    if (completed) handlers.onFinish() else handlers.onCancel()
}
