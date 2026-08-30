package io.github.m1n1m1.easymatic.feature.grapheditor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * A box selection in progress.
 *
 * Corners are in **graph units**, not screen px, so the capture set does not
 * change when the canvas is zoomed or panned mid-drag. [captured] is recomputed on
 * every move so the nodes it would take can be highlighted live — a box drawn with
 * a thumb over cards whose edges are under it is otherwise pure guesswork.
 */
data class Marquee(
    val startGraph: Offset,
    val currentGraph: Offset,
    val captured: Selection = Selection.EMPTY,
) {
    /** Normalised, so dragging up and to the left works as well as down and right. */
    val rect: Rect
        get() = Rect(
            left = min(startGraph.x, currentGraph.x),
            top = min(startGraph.y, currentGraph.y),
            right = max(startGraph.x, currentGraph.x),
            bottom = max(startGraph.y, currentGraph.y),
        )
}
