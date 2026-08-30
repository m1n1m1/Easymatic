package io.github.m1n1m1.easymatic.feature.grapheditor

import androidx.compose.ui.geometry.Offset

/**
 * Pure state steps for the box selection.
 *
 * Marquee state lives in [GraphEditorUiState] rather than in a `remember`, for the
 * same reason `pendingConnection` does: it needs the workflow to compute what it
 * would capture, and it has to be cancellable by the two-finger gate, which is a
 * sibling of any composable that could own local state.
 */

/** A long press on empty canvas started a box at [graphPos]. */
fun GraphEditorUiState.withMarqueeStarted(graphPos: Offset): GraphEditorUiState =
    copy(interaction = interaction.copy(marquee = Marquee(graphPos, graphPos)))

/**
 * The box was dragged to [graphPos].
 *
 * Recomputes the capture set every frame rather than only on release, so the nodes
 * the box would take can be highlighted while it is being drawn.
 */
fun GraphEditorUiState.withMarqueeMoved(graphPos: Offset): GraphEditorUiState {
    val marquee = interaction.marquee ?: return this
    val moved = marquee.copy(currentGraph = graphPos)
    return copy(interaction = interaction.copy(marquee = moved.copy(captured = marqueeCapture(workflow, moved.rect))))
}

/**
 * The finger lifted: the box's contents become the selection.
 *
 * It **replaces** rather than adds. A marquee is a fresh statement of what you
 * want, and a box that merged into an existing selection would have no way to say
 * "just these" — while adding to a selection already has a gesture, the tap in
 * multi-select mode.
 *
 * Multi-select turns on for anything captured, including a single node, so the
 * taps that follow refine the box rather than throwing it away. A box that caught
 * nothing simply clears, which is also how you back out of one.
 */
fun GraphEditorUiState.withMarqueeCommitted(): GraphEditorUiState {
    val captured = interaction.marquee?.captured ?: return this
    return copy(
        selection = captured,
        interaction = interaction.copy(isMultiSelect = captured.isNotEmpty, marquee = null),
    )
}

/** The box was abandoned — cancelled, or taken over by the canvas. Selection untouched. */
fun GraphEditorUiState.withMarqueeCancelled(): GraphEditorUiState =
    if (interaction.marquee == null) this else copy(interaction = interaction.copy(marquee = null))
