package com.example.ottomatic.feature.grapheditor

import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.domain.model.Workflow

/**
 * What the editor has selected.
 *
 * Nodes and edges live in **one** value rather than two, because every consumer
 * treats them as one collection: the delete button removes both, a marquee
 * captures both, and "is anything selected?" is one question. Splitting them only
 * pushes the union back onto each call site.
 *
 * A selection of more than one is reachable through the marquee and through
 * long-press multi-select; everything else in the editor still produces a
 * selection of exactly one, which is why [singleNodeId] exists.
 */
data class Selection(
    val nodeIds: Set<NodeId> = emptySet(),
    val connectionIds: Set<String> = emptySet(),
) {

    val isEmpty: Boolean get() = nodeIds.isEmpty() && connectionIds.isEmpty()

    val isNotEmpty: Boolean get() = !isEmpty

    val size: Int get() = nodeIds.size + connectionIds.size

    /**
     * The one node to configure, or null when the selection is anything else.
     *
     * The config sheet edits a single node's fields and has no meaning for an
     * edge or for several nodes at once, so the Configure button keys off this
     * rather than off "is anything selected".
     */
    val singleNodeId: NodeId? get() = nodeIds.singleOrNull()?.takeIf { connectionIds.isEmpty() }

    operator fun contains(nodeId: NodeId): Boolean = nodeId in nodeIds

    operator fun contains(connectionId: String): Boolean = connectionId in connectionIds

    companion object {
        val EMPTY = Selection()

        fun ofNode(nodeId: NodeId) = Selection(nodeIds = setOf(nodeId))

        fun ofConnection(connectionId: String) = Selection(connectionIds = setOf(connectionId))
    }
}

/** Adds [nodeId] if it is absent, removes it if it is present. */
fun Selection.toggleNode(nodeId: NodeId): Selection = copy(
    nodeIds = if (nodeId in nodeIds) nodeIds - nodeId else nodeIds + nodeId,
)

/** Adds [connectionId] if it is absent, removes it if it is present. */
fun Selection.toggleConnection(connectionId: String): Selection = copy(
    connectionIds = if (connectionId in connectionIds) {
        connectionIds - connectionId
    } else {
        connectionIds + connectionId
    },
)

/**
 * Removes every selected node — along with each edge incident to it, which would
 * otherwise dangle — and every separately selected edge.
 *
 * One pass over each list rather than one pass per selected node: a marquee over a
 * dense graph can easily select twenty nodes.
 */
fun Workflow.withoutSelection(selection: Selection): Workflow {
    if (selection.isEmpty) return this
    val nodeIds = selection.nodeIds
    val connectionIds = selection.connectionIds
    return copy(
        nodes = nodes.filterNot { it.id in nodeIds },
        execConnections = execConnections.filterNot {
            it.id in connectionIds || it.fromNodeId in nodeIds || it.toNodeId in nodeIds
        },
        dataConnections = dataConnections.filterNot {
            it.id in connectionIds || it.fromNodeId in nodeIds || it.toNodeId in nodeIds
        },
    )
}
