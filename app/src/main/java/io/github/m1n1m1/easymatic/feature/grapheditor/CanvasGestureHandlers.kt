package io.github.m1n1m1.easymatic.feature.grapheditor

import androidx.compose.ui.geometry.Offset

/**
 * What a single finger on empty canvas can turn out to have meant.
 *
 * All positions and deltas are **screen px**: the canvas surface sits outside
 * `NodeLayer`'s `graphicsLayer`, so nothing here has the pan/zoom applied. The
 * marquee converts to graph units on the way into state, where it has to be
 * zoom-independent; the pan does not, because that is what it is adjusting.
 */
data class CanvasGestureHandlers(
    /** Tapped without moving. */
    val onTap: (positionPx: Offset) -> Unit,
    /** One frame of a one-finger pan. */
    val onPan: (deltaPx: Offset) -> Unit,
    /** Held still: a box selection starts here. */
    val onMarqueeStart: (positionPx: Offset) -> Unit,
    val onMarqueeMove: (positionPx: Offset) -> Unit,
    val onMarqueeCommit: () -> Unit,
    val onMarqueeCancel: () -> Unit,
)
