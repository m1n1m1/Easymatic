package io.github.m1n1m1.easymatic.feature.grapheditor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.m1n1m1.easymatic.domain.model.PortKind
import io.github.m1n1m1.easymatic.domain.model.Workflow
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.registry.NodeTypeRegistry
import io.github.m1n1m1.easymatic.domain.registry.effectiveInputPorts
import io.github.m1n1m1.easymatic.domain.registry.effectiveOutputPorts

/**
 * Placed bounds of [node] in graph units.
 *
 * Width comes from the node's **effective** port counts rather than its static
 * definition, the same way [portPositionOf] resolves a port: a dynamic-port node
 * like `action.break` is as wide as the struct wired into it, and the declared
 * width would put its right-hand ports outside its own bounds.
 */
fun nodeBounds(workflow: Workflow, node: WorkflowNode): Rect? {
    val definition = NodeTypeRegistry.byId(node.typeId) ?: return null
    val inputs = effectiveInputPorts(definition, workflow, node)
    val outputs = effectiveOutputPorts(definition, workflow, node)
    val width = GraphGeometry.nodeWidth(node.typeId, inputs.size, outputs.size)
    return Rect(node.x, node.y, node.x + width, node.y + GraphGeometry.NODE_HEIGHT)
}

/**
 * Everything [rect] touches, in graph coordinates.
 *
 * **Intersection, not containment.** On a phone the box is drawn with a thumb over
 * a canvas that may be at 0.3x, where requiring a node to be fully enclosed makes
 * a multi-select nearly impossible to land. Overlapping is the forgiving rule and
 * the one a touch user expects; the cost is that a box brushing a node's corner
 * takes it, which is visible live and easy to correct by dragging back.
 */
fun marqueeCapture(workflow: Workflow, rect: Rect): Selection = Selection(
    nodeIds = workflow.nodes
        .filter { nodeBounds(workflow, it)?.overlaps(rect) == true }
        .mapTo(HashSet()) { it.id },
    connectionIds = workflow.edgeCurves()
        .filter { (_, start, end) -> GraphGeometry.edgeIntersects(start, end, rect) }
        .mapTo(HashSet()) { (id, _, _) -> id },
)

/** Every edge as `(id, start, end)` in graph units, skipping any whose ports no longer resolve. */
private fun Workflow.edgeCurves(): List<Triple<String, Offset, Offset>> = buildList {
    execConnections.forEach { connection ->
        addCurve(
            this@edgeCurves,
            connection.id,
            PortRef(connection.fromNodeId, connection.fromPort, isOutput = true, kind = PortKind.EXECUTION),
            PortRef(connection.toNodeId, connection.toPort, isOutput = false, kind = PortKind.EXECUTION),
        )
    }
    dataConnections.forEach { connection ->
        addCurve(
            this@edgeCurves,
            connection.id,
            PortRef(connection.fromNodeId, connection.fromPort, isOutput = true, kind = PortKind.DATA),
            PortRef(connection.toNodeId, connection.toPort, isOutput = false, kind = PortKind.DATA),
        )
    }
}

private fun MutableList<Triple<String, Offset, Offset>>.addCurve(
    workflow: Workflow,
    id: String,
    from: PortRef,
    to: PortRef,
) {
    val start = portPositionOf(workflow, from) ?: return
    val end = portPositionOf(workflow, to) ?: return
    add(Triple(id, start, end))
}
