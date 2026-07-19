package com.example.ottomatic.feature.grapheditor

import androidx.compose.ui.geometry.Offset
import com.example.ottomatic.domain.model.NodeTypeDefinition
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.model.WorkflowNode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Layout math for the node graph. All values are in graph units,
 * which map 1:1 to dp at zoom level 1.
 */
object GraphGeometry {

    const val NODE_HEIGHT = 72f
    const val NODE_MIN_WIDTH = 190f
    const val PORT_SPACING = 30f
    const val PORT_RADIUS = 7f
    const val PORT_SNAP_RADIUS = 36f
    const val EDGE_HIT_RADIUS = 16f
    const val MIN_ZOOM = 0.3f
    const val MAX_ZOOM = 2.5f

    private const val HORIZONTAL_PADDING = 28f
    private const val BEZIER_MIN = 48f
    private const val BEZIER_MAX = 170f
    private const val EDGE_SAMPLES = 32
    private const val CURVE_TENSION = 0.5f
    private const val BACKWARD_TENSION = 0.6f

    /** Nodes flow top-to-bottom, so width grows with port count and height is fixed. */
    fun nodeWidth(definition: NodeTypeDefinition): Float {
        val portCount = max(definition.inputPorts.size, definition.outputPorts.size)
        return max(NODE_MIN_WIDTH, portCount * PORT_SPACING + HORIZONTAL_PADDING)
    }

    /**
     * Width for a placed node, computed from its **effective** port counts
     * (which may differ from the static [NodeTypeDefinition] for dynamic-port
     * nodes like `action.break` / `action.make`).
     */
    fun nodeWidth(inputPortCount: Int, outputPortCount: Int): Float {
        val portCount = max(inputPortCount, outputPortCount)
        return max(NODE_MIN_WIDTH, portCount * PORT_SPACING + HORIZONTAL_PADDING)
    }

    /** Position of a port relative to the node's top-left corner (static definition). */
    fun portOffset(definition: NodeTypeDefinition, port: Port): Offset {
        val isOutput = port.direction == com.example.ottomatic.domain.model.Direction.OUT
        val list = if (isOutput) definition.outputPorts else definition.inputPorts
        return portOffsetIn(list, port, nodeWidth(definition))
    }

    /**
     * Position of a port relative to the node's top-left corner, using the
     * placed node's **effective** port lists (for dynamic-port nodes).
     */
    fun portOffset(
        inputPorts: List<Port>,
        outputPorts: List<Port>,
        width: Float,
        port: Port,
    ): Offset {
        val isOutput = port.direction == com.example.ottomatic.domain.model.Direction.OUT
        val list = if (isOutput) outputPorts else inputPorts
        return portOffsetIn(list, port, width)
    }

    private fun portOffsetIn(list: List<Port>, port: Port, width: Float): Offset {
        val index = list.indexOfFirst { it.name == port.name }.coerceAtLeast(0)
        val count = list.size
        val x = width / 2f + (index - (count - 1) / 2f) * PORT_SPACING
        val y = if (port.direction == com.example.ottomatic.domain.model.Direction.OUT) NODE_HEIGHT else 0f
        return Offset(x, y)
    }

    /** Absolute position of a port in graph coordinates (static definition). */
    fun portPosition(
        node: WorkflowNode,
        definition: NodeTypeDefinition,
        port: Port,
    ): Offset = Offset(node.x, node.y) + portOffset(definition, port)

    /**
     * Absolute position of a port in graph coordinates, using the placed
     * node's **effective** port lists and width (for dynamic-port nodes).
     */
    fun portPosition(
        node: WorkflowNode,
        inputPorts: List<Port>,
        outputPorts: List<Port>,
        width: Float,
        port: Port,
    ): Offset = Offset(node.x, node.y) + portOffset(inputPorts, outputPorts, width, port)

    /** Control point vertical reach for the connection bezier. */
    fun bezierReach(start: Offset, end: Offset): Float {
        val dy = abs(end.y - start.y)
        val base = max(BEZIER_MIN, min(BEZIER_MAX, dy * CURVE_TENSION))
        return if (end.y < start.y) {
            max(base, min(BEZIER_MAX, abs(end.x - start.x) * BACKWARD_TENSION + BEZIER_MIN))
        } else {
            base
        }
    }

    /** Point on the connection cubic bezier at parameter [t] in 0..1. */
    @Suppress("MagicNumber") // Standard cubic bezier polynomial coefficients.
    fun edgePoint(start: Offset, end: Offset, t: Float): Offset {
        val reach = bezierReach(start, end)
        val c1 = Offset(start.x, start.y + reach)
        val c2 = Offset(end.x, end.y - reach)
        val u = 1f - t
        return Offset(
            u * u * u * start.x + 3f * u * u * t * c1.x + 3f * u * t * t * c2.x + t * t * t * end.x,
            u * u * u * start.y + 3f * u * u * t * c1.y + 3f * u * t * t * c2.y + t * t * t * end.y,
        )
    }

    /** Shortest sampled distance from [point] to the edge curve. */
    fun distanceToEdge(start: Offset, end: Offset, point: Offset): Float {
        var best = Float.MAX_VALUE
        for (i in 0..EDGE_SAMPLES) {
            val t = i / EDGE_SAMPLES.toFloat()
            val d = (edgePoint(start, end, t) - point).getDistance()
            if (d < best) best = d
        }
        return best
    }
}
