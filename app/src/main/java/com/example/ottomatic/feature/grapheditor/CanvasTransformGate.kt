package com.example.ottomatic.feature.grapheditor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope

/** Two fingers is what makes a gesture the canvas's rather than a node's. */
private const val MULTI_TOUCH_POINTERS = 2

/**
 * Two fingers on the canvas are **always** a canvas gesture, whatever a child
 * believes it is doing.
 *
 * This is the whole fix for "I have to find empty space before I can zoom".
 * Placed on the outer `GraphCanvas` box, it runs on [PointerEventPass.Initial],
 * which reaches an ancestor *before* any node card sees the same event on
 * [PointerEventPass.Main]. Once a second pointer is down it consumes every
 * change, and consumption is how a child gesture learns it lost: a node's drag
 * loop sees `isConsumed`, `drag()` returns false, and it reports a **cancel** —
 * which is why [NodeGestureHandlers.onCancel] has to be a different callback from
 * `onFinish`, or the pinch would leave the node nudged.
 *
 * Nothing here runs for a single finger. One-finger arbitration is still settled
 * the ordinary way, by whichever layer consumes the down first.
 *
 * Positions reported here are **screen px** — the gate sits above `NodeLayer`'s
 * `graphicsLayer`, so it never sees the canvas transform applied. That matches
 * what [GraphEditorViewModel.onZoom] expects. Deltas inside a node card come from
 * within that layer and are pre-scale instead; the asymmetry is real and both
 * sides are commented.
 */
suspend fun PointerInputScope.awaitCanvasTransformGate(
    onTakeOver: () -> Unit,
    onTransform: (centroidPx: Offset, zoom: Float, panPx: Offset) -> Unit,
) = awaitEachGesture {
    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    var driving = false
    var pressedCount = 1
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val pressed = event.changes.count { it.pressed }
        if (pressed == 0) break
        val pointerSetChanged = pressed != pressedCount
        pressedCount = pressed
        if (!driving && pressed >= MULTI_TOUCH_POINTERS) {
            driving = true
            onTakeOver()
        }
        if (driving) {
            // Adding or lifting a finger jumps the centroid. That frame carries no
            // real movement, so it is swallowed rather than reported as a big pan.
            if (!pointerSetChanged) {
                onTransform(event.calculateCentroid(useCurrent = true), event.calculateZoom(), event.calculatePan())
            }
            event.changes.forEach { it.consume() }
        }
    }
    // `driving` is latched until every finger is off the glass, mirroring
    // detectTransformGestures. Without that, lifting one finger mid-pinch would
    // hand the remaining one back to whatever node is under it.
}
