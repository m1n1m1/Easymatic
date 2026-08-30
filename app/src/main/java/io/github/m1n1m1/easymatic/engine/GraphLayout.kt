package io.github.m1n1m1.easymatic.engine

import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.domain.model.NodeKind
import io.github.m1n1m1.easymatic.domain.model.Workflow
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.registry.NodeTypeRegistry

/**
 * Where the nodes somebody else placed should sit.
 *
 * **The assistant is never asked for coordinates.** A model choosing pixel positions
 * spends turns on something it cannot see and gets wrong anyway, and the alternative —
 * placing each node wherever it happens to land — produces a pile. So the tools carry
 * no `x`/`y` at all and this arranges the result once the turn is over.
 *
 * It lays out **only the nodes it is given** and never touches the rest. A macro the
 * user has already arranged by hand is theirs; adding two nodes to it must not
 * reflow the twenty that were already where they wanted them.
 */
object GraphLayout {

    /**
     * Horizontal pitch between siblings, in graph units.
     *
     * Wider than `GraphGeometry.NODE_MIN_WIDTH` so two branches of an `action.if` do
     * not touch. The geometry itself lives in `feature/` and is not reachable here,
     * which is fine: this is a *starting* arrangement, and anything the user drags
     * afterwards is theirs.
     */
    const val COLUMN_WIDTH = 260f

    /** Vertical pitch between layers. The graph flows top to bottom. */
    const val ROW_HEIGHT = 170f

    /**
     * Where a node being placed right now should go, before anything is wired to it.
     *
     * A staging column below whatever is already on the canvas, stepping down one row
     * per node. It matters because the assistant's edits land **live** — the user is
     * watching the canvas — so nodes have to appear somewhere sensible one at a time
     * rather than piling on the origin until [arrange] sorts them out at the end.
     */
    fun provisionalPosition(workflow: Workflow): Pair<Float, Float> {
        val x = workflow.nodes.minOfOrNull { it.x } ?: 0f
        val y = workflow.nodes.maxOfOrNull { it.y + ROW_HEIGHT } ?: 0f
        return x to y
    }

    /**
     * [workflow] with every node in [nodeIds] laid out in exec-topological layers,
     * anchored below whatever was already on the canvas.
     *
     * Two passes, because the graph has two channels and they answer different
     * questions. Triggers and actions sit on the **exec** wire and get the layering;
     * values and transforms have no exec position at all — they are pulled — so they
     * are placed beside whichever node reads them, which is where somebody looking for
     * them would expect to find them.
     */
    fun arrange(workflow: Workflow, nodeIds: Set<NodeId>): Workflow {
        val subject = workflow.nodes.filter { it.id in nodeIds }
        if (subject.isEmpty()) return workflow

        val existing = workflow.nodes.filterNot { it.id in nodeIds }
        val originX = existing.minOfOrNull { it.x } ?: 0f
        val originY = existing.maxOfOrNull { it.y + ROW_HEIGHT } ?: 0f

        val onExecWire = subject.filter { isOnExecWire(it) }.map { it.id }.toSet()
        val placed = mutableMapOf<NodeId, Pair<Float, Float>>()

        val layers = layersOf(workflow, onExecWire)
        val order = subject.map { it.id }
        layers.entries
            .groupBy({ it.value }, { it.key })
            .toSortedMap()
            .forEach { (layer, ids) ->
                ids.sortedBy { order.indexOf(it) }.forEachIndexed { column, id ->
                    placed[id] = (originX + column * COLUMN_WIDTH) to (originY + layer * ROW_HEIGHT)
                }
            }

        // The pull side, after the exec side, so a consumer's position is known by the
        // time whatever feeds it asks for one.
        subject.filterNot { it.id in onExecWire }.forEach { node ->
            placed[node.id] = pullSidePosition(workflow, node, placed, originX, originY)
        }

        return workflow.copy(
            nodes = workflow.nodes.map { node ->
                placed[node.id]?.let { (x, y) -> node.copy(x = x, y = y) } ?: node
            },
        )
    }

    /**
     * Longest path from a root, over the exec edges **inside** [ids].
     *
     * Longest rather than shortest so a join node lands below both of its branches
     * rather than overlapping the shorter one. Relaxation is bounded by the node count,
     * which is also what stops an exec cycle here: a graph the validator has not seen
     * yet may still contain one, and a layout that hangs is worse than a layout that
     * flattens.
     */
    private fun layersOf(workflow: Workflow, ids: Set<NodeId>): Map<NodeId, Int> {
        val layers = ids.associateWith { 0 }.toMutableMap()
        val edges = workflow.execConnections.filter { it.fromNodeId in ids && it.toNodeId in ids }
        var rounds = 0
        var changed = true
        while (changed && rounds < ids.size) {
            changed = false
            rounds++
            for (edge in edges) {
                val want = (layers[edge.fromNodeId] ?: 0) + 1
                if ((layers[edge.toNodeId] ?: 0) < want) {
                    layers[edge.toNodeId] = want
                    changed = true
                }
            }
        }
        return layers
    }

    /**
     * Beside the node that reads this one, or in a column of its own.
     *
     * A value or transform is pulled immediately before its consumer, so that is where
     * it belongs on the canvas: one row up and one column left, which is the shape the
     * autocast Convert already takes when it lands mid-wire. A chain of transforms
     * steps further left as each one finds its own consumer already placed.
     */
    private fun pullSidePosition(
        workflow: Workflow,
        node: WorkflowNode,
        placed: Map<NodeId, Pair<Float, Float>>,
        originX: Float,
        originY: Float,
    ): Pair<Float, Float> {
        val consumer = workflow.dataConnections
            .firstOrNull { it.fromNodeId == node.id && placed.containsKey(it.toNodeId) }
            ?.let { placed[it.toNodeId] }
        return when (consumer) {
            null -> (originX - COLUMN_WIDTH) to originY
            else -> (consumer.first - COLUMN_WIDTH) to (consumer.second - ROW_HEIGHT)
        }
    }

    /** Whether this node has an exec position at all. Values and transforms do not. */
    private fun isOnExecWire(node: WorkflowNode): Boolean =
        when (NodeTypeRegistry.byId(node.typeId)?.kind) {
            NodeKind.TRIGGER, NodeKind.ACTION -> true
            // An unknown type is laid out as if it were an action rather than dropped:
            // a plugin whose app is not installed right now still occupies the canvas.
            null -> true
            else -> false
        }
}
