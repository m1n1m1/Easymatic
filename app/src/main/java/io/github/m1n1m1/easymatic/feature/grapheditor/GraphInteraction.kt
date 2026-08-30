package io.github.m1n1m1.easymatic.feature.grapheditor

/**
 * Editor state that only a finger on the glass produces.
 *
 * Grouped into one value for two reasons. A gesture in flight has to be
 * discardable as a unit — the two-finger canvas gate cancels whatever a single
 * finger was doing, and "reset the interaction" should be one assignment rather
 * than three. And [GraphEditorUiState] is already at the edge of what reads well
 * as a parameter list; three more top-level fields would push it past it.
 */
data class GraphInteraction(
    /**
     * Whether taps add to and remove from the selection instead of replacing it.
     *
     * Entered by long-pressing a node or by committing a marquee, left by
     * clearing the selection. It is a mode, so it is deliberately visible in the
     * top bar rather than inferred from the selection size — a selection of one
     * behaves differently depending on it.
     */
    val isMultiSelect: Boolean = false,

    /**
     * The finger currently on a node card, and everything it would have to undo.
     * Null between gestures.
     */
    val nodeDrag: NodeDragSession? = null,

    /** The box selection being dragged out on empty canvas. Null when there is none. */
    val marquee: Marquee? = null,
)
