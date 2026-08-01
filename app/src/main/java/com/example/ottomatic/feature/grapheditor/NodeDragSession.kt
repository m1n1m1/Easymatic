package com.example.ottomatic.feature.grapheditor

import androidx.compose.ui.geometry.Offset
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.domain.model.Workflow

/**
 * Everything a finger on a node card might have to undo.
 *
 * The snapshot is taken when the finger **lands**, not when the drag starts, so a
 * long-press that joined the node to the selection is undone too. The rule the
 * whole gesture layer is built around is "an accidental two-finger takeover
 * changes nothing", and *nothing* has to include what was selected — otherwise a
 * pinch that happened to start on a node quietly rewrites the selection.
 *
 * [moved] is what decides whether releasing writes to disk: a press that only
 * selected must not schedule a save of an unchanged graph.
 */
data class NodeDragSession(
    val previousSelection: Selection,
    val previousMultiSelect: Boolean,
    val dragNodeIds: Set<NodeId> = emptySet(),
    val originPositions: Map<NodeId, Offset> = emptyMap(),
    val moved: Boolean = false,
)

/**
 * The nodes a drag on [nodeId] should move: the whole selection when [nodeId] is
 * part of it, otherwise just [nodeId] itself.
 *
 * Dragging a node that is *not* in the selection is how you move something without
 * first dismissing a multi-selection, so it must not drag the others along.
 */
fun dragSetFor(selection: Selection, nodeId: NodeId): Set<NodeId> =
    if (nodeId in selection) selection.nodeIds else setOf(nodeId)

/** Current positions of [nodeIds], for a snapshot that a cancel can restore. */
fun Workflow.positionsOf(nodeIds: Set<NodeId>): Map<NodeId, Offset> =
    nodes.filter { it.id in nodeIds }.associate { it.id to Offset(it.x, it.y) }

/** Shifts every node in [nodeIds] by [deltaGraph]; everything else is untouched. */
fun Workflow.movedBy(nodeIds: Set<NodeId>, deltaGraph: Offset): Workflow {
    if (nodeIds.isEmpty() || deltaGraph == Offset.Zero) return this
    return copy(
        nodes = nodes.map { node ->
            if (node.id in nodeIds) node.copy(x = node.x + deltaGraph.x, y = node.y + deltaGraph.y) else node
        },
    )
}

/** Puts the nodes named in [positions] back where they were. */
fun Workflow.withNodePositions(positions: Map<NodeId, Offset>): Workflow {
    if (positions.isEmpty()) return this
    return copy(
        nodes = nodes.map { node ->
            positions[node.id]?.let { node.copy(x = it.x, y = it.y) } ?: node
        },
    )
}
