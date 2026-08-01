package com.example.ottomatic.feature.grapheditor

import androidx.compose.ui.geometry.Offset
import com.example.ottomatic.core.model.NodeId

/**
 * Pure state steps for a finger on a node card, from landing to lifting.
 *
 * Split from [SelectionReducer] purely by subject; both exist for the same reason
 * (see the note there on why nothing testable may live in the ViewModel).
 */

/**
 * A finger landed on a node card.
 *
 * Deliberately changes nothing visible: it only records what a cancel would have
 * to restore. That is what stops a pinch which happens to start on a node from
 * flashing a selection before the two-finger gate takes over.
 */
fun GraphEditorUiState.withNodeGestureBegun(): GraphEditorUiState = copy(
    interaction = interaction.copy(
        nodeDrag = NodeDragSession(
            previousSelection = selection,
            previousMultiSelect = interaction.isMultiSelect,
        ),
    ),
)

/**
 * The finger on [nodeId] crossed the slop and is now dragging.
 *
 * A node that is not already selected becomes the selection first — you cannot
 * drag something without grabbing it. The drag set is fixed here rather than
 * recomputed per frame, so a node passing under another one mid-drag cannot join
 * it.
 */
fun GraphEditorUiState.withNodeDragBegun(nodeId: NodeId): GraphEditorUiState {
    val session = interaction.nodeDrag ?: return this
    val grabbed = if (nodeId in selection) this else withSelection(Selection.ofNode(nodeId))
    val dragIds = dragSetFor(grabbed.selection, nodeId)
    return grabbed.copy(
        interaction = grabbed.interaction.copy(
            nodeDrag = session.copy(
                dragNodeIds = dragIds,
                originPositions = workflow.positionsOf(dragIds),
            ),
        ),
    )
}

/** One frame of a node drag, in graph units. */
fun GraphEditorUiState.withDragDelta(deltaGraph: Offset): GraphEditorUiState {
    val session = interaction.nodeDrag?.takeIf { it.dragNodeIds.isNotEmpty() } ?: return this
    return copy(
        workflow = workflow.movedBy(session.dragNodeIds, deltaGraph),
        interaction = interaction.copy(nodeDrag = session.copy(moved = true)),
    )
}

/** The finger lifted normally. Positions stay where they were dragged to. */
fun GraphEditorUiState.withNodeGestureEnded(): GraphEditorUiState =
    copy(interaction = interaction.copy(nodeDrag = null))

/**
 * The gesture was cancelled or taken over: put the graph back exactly as it was
 * when the finger landed — positions, selection and mode.
 *
 * Idempotent, because it is called twice for the same gesture. The two-finger gate
 * cancels on the Initial pointer pass; the node's own drag loop then sees its
 * changes consumed and reports a cancel of its own on the next Main pass.
 */
fun GraphEditorUiState.withNodeGestureReverted(): GraphEditorUiState {
    val session = interaction.nodeDrag ?: return this
    return copy(
        workflow = workflow.withNodePositions(session.originPositions),
        selection = session.previousSelection,
        interaction = interaction.copy(
            isMultiSelect = session.previousMultiSelect,
            nodeDrag = null,
        ),
    )
}

/** True when releasing the finger should write to disk. */
val GraphEditorUiState.hasUnsavedNodeMove: Boolean
    get() = interaction.nodeDrag?.moved == true
