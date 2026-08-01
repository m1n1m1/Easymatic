package com.example.ottomatic.feature.grapheditor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange

/**
 * One finger on empty canvas: tap to select an edge or clear, drag to pan, hold to
 * draw a marquee.
 *
 * Two fingers never reach here — [awaitCanvasTransformGate] consumes them on the
 * Initial pass, which shows up in this loop as `drag()` returning false.
 *
 * `requireUnconsumed = true` is what keeps this off a press that a node card or a
 * port handle already claimed. The detector it replaces used
 * `detectTransformGestures`, which does not require it, and that is part of why a
 * finger landing on a node used to behave ambiguously.
 */
suspend fun PointerInputScope.detectCanvasSurfaceGestures(
    handlers: CanvasGestureHandlers,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = true)
    when (val intent = awaitPressIntent(down, viewConfiguration.longPressTimeoutMillis)) {
        is PressIntent.Tap -> handlers.onTap(down.position)

        is PressIntent.Drag -> {
            handlers.onPan(intent.overSlop)
            // A pan has nothing to undo, so a pre-empted one needs no cancel path.
            drag(down.id) { change ->
                // Read the delta BEFORE consuming: `positionChange()` returns
                // Offset.Zero for a consumed change, so the other order reports
                // every frame as no movement and the canvas never pans.
                val delta = change.positionChange()
                change.consume()
                handlers.onPan(delta)
            }
        }

        is PressIntent.LongPress -> {
            handlers.onMarqueeStart(down.position)
            val completed = drag(down.id) { change ->
                change.consume()
                handlers.onMarqueeMove(change.position)
            }
            if (completed) handlers.onMarqueeCommit() else handlers.onMarqueeCancel()
        }

        is PressIntent.PreEmpted -> Unit
    }
}
