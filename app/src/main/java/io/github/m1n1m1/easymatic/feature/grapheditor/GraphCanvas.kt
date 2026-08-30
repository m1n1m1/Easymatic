@file:Suppress("TooManyFunctions")

package io.github.m1n1m1.easymatic.feature.grapheditor

import io.github.m1n1m1.easymatic.core.model.PortName
import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.model.ConfigKey
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Constraints
import io.github.m1n1m1.easymatic.domain.model.Port
import io.github.m1n1m1.easymatic.domain.model.PortKind
import io.github.m1n1m1.easymatic.domain.model.Workflow
import io.github.m1n1m1.easymatic.domain.registry.NodeTypeRegistry
import io.github.m1n1m1.easymatic.domain.registry.effectiveInputPorts
import io.github.m1n1m1.easymatic.domain.registry.effectiveOutputPorts
import io.github.m1n1m1.easymatic.engine.trigger.ManualTrigger
import io.github.m1n1m1.easymatic.engine.validation.GraphValidation

private const val GRID_SPACING = 26f
private const val MIN_GRID_SPACING_PX = 16f
private const val GRID_DOT_BASE = 3f
private const val GRID_DOT_MIN = 2f
private const val GRID_DOT_MAX = 5f
private const val EDGE_WIDTH = 2.4f
private const val EDGE_SELECTED_WIDTH = 3.4f
private const val ENDPOINT_RADIUS = 3.2f
private const val ARROW_SIZE = 6f
private const val ARROW_MID_T = 0.5f
private const val ARROW_AHEAD_T = 0.55f
private const val ARROW_BACK_RATIO = 0.7f
private const val ARROW_WIDTH_RATIO = 0.75f
private const val MIN_ARROW_DIRECTION = 0.01f
private const val DASH_ON = 9f
private const val DASH_OFF = 7f
private const val SNAP_HIGHLIGHT_RADIUS = 14f
private const val SNAP_HIGHLIGHT_ALPHA = 0.3f

/**
 * [validation] arrives as a value rather than a flow, unlike the console's:
 * it changes only when the graph does, and the canvas is already recomposing for
 * that. Collecting it here instead would just add a second subscription to the
 * same edits.
 */
@Composable
fun GraphCanvas(
    state: GraphEditorUiState,
    validation: GraphValidation,
    viewModel: GraphEditorViewModel,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clipToBounds()
            .background(EditorColors.canvasBackground)
            // Outermost on purpose: this is an ancestor of every node card, so it
            // sees a second finger land before any of them do. See the file.
            .pointerInput(viewModel) {
                awaitCanvasTransformGate(
                    onTakeOver = { viewModel.beginCanvasTransform() },
                    onTransform = { centroid, zoom, pan -> viewModel.onZoom(centroid, zoom, pan) },
                )
            },
    ) {
        BackgroundLayer(state, validation, viewModel)
        NodeLayer(state, validation, viewModel)
        MarqueeLayer(state)
    }
}

@Composable
private fun BackgroundLayer(
    state: GraphEditorUiState,
    validation: GraphValidation,
    viewModel: GraphEditorViewModel,
) {
    val transform = state.transform
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(viewModel) {
                detectCanvasSurfaceGestures(
                    CanvasGestureHandlers(
                        onTap = { positionPx ->
                            val current = viewModel.uiState.value
                            val hit = edgeHitTest(current.workflow, current.transform.toGraph(positionPx, density))
                            if (hit != null) viewModel.tapConnection(hit) else viewModel.clearSelection()
                            viewModel.clearRevealedLabel()
                        },
                        onPan = { delta -> viewModel.onPan(delta) },
                        // Converted to graph units on the way in, so the box keeps
                        // hold of the same nodes if the canvas moves under it.
                        onMarqueeStart = { positionPx ->
                            viewModel.startMarquee(viewModel.uiState.value.transform.toGraph(positionPx, density))
                        },
                        onMarqueeMove = { positionPx ->
                            viewModel.moveMarquee(viewModel.uiState.value.transform.toGraph(positionPx, density))
                        },
                        onMarqueeCommit = { viewModel.commitMarquee() },
                        onMarqueeCancel = { viewModel.cancelMarquee() },
                    ),
                )
            },
    ) {
        drawGrid(transform)
        val densityScale = transform.scale * density
        withTransform({
            translate(transform.offset.x, transform.offset.y)
            scale(densityScale, densityScale, pivot = Offset.Zero)
        }) {
            drawConnections(state, validation.blockedConnections)
            drawPendingConnection(state)
        }
    }
}

@Composable
private fun NodeLayer(
    state: GraphEditorUiState,
    validation: GraphValidation,
    viewModel: GraphEditorViewModel,
) {
    val transform = state.transform
    Layout(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = transform.offset.x
                translationY = transform.offset.y
                scaleX = transform.scale
                scaleY = transform.scale
                transformOrigin = TransformOrigin(0f, 0f)
            },
        content = {
            val selection = state.selection
            val captured = state.interaction.marquee?.captured
            val haptics = LocalHapticFeedback.current
            state.workflow.nodes.forEach { node ->
                val definition = NodeTypeRegistry.byId(node.typeId) ?: return@forEach
                key(node.id) {
                    NodeCard(
                        node = node,
                        definition = definition,
                        workflow = state.workflow,
                        highlight = when {
                            node.id in selection -> NodeHighlight.SELECTED
                            captured != null && node.id in captured -> NodeHighlight.CANDIDATE
                            else -> NodeHighlight.NONE
                        },
                        problem = validation.severityFor(node.id),
                        isRunning = node.id in state.runningNodes,
                        // The one node whose card carries a control. Its
                        // counterpart is `nodeSubtitle`'s geofence branch: the
                        // first thing in a card body that varies by node type.
                        onRunToggle = if (node.typeId == ManualTrigger.TYPE_ID) {
                            { viewModel.toggleManualRun(node.id) }
                        } else {
                            null
                        },
                        hoverPort = state.pendingConnection?.hoverPort,
                        revealedLabel = state.revealedLabel,
                        pendingFrom = state.pendingConnection?.from,
                        gestures = NodeGestureHandlers(
                            onPress = { viewModel.beginNodeGesture() },
                            onTap = { viewModel.tapNode(node.id) },
                            onLongPress = {
                                // A mode change with no feedback is not discoverable on
                                // a surface with no hover and no cursor.
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.longPressNode(node.id)
                            },
                            onDragStart = { viewModel.beginNodeDrag(node.id) },
                            onDrag = { delta -> viewModel.dragSelectedNodes(delta) },
                            onFinish = { viewModel.endNodeGesture() },
                            onCancel = { viewModel.cancelNodeGesture() },
                        ),
                        onPortDragStart = { ref -> viewModel.startPortDrag(ref) },
                        onPortDrag = { delta -> viewModel.updatePortDrag(delta) },
                        onPortDragEnd = { viewModel.endPortDrag() },
                        onPortDragCancel = { viewModel.cancelPortDrag() },
                        onToggleRevealedLabel = { ref -> viewModel.toggleRevealedLabel(ref) },
                    )
                }
            }
        },
    ) { measurables, constraints ->
        // Cards are measured against nothing, not against the viewport.
        //
        // A card carries its own position in a `Modifier.offset` and its own width
        // from its port count, so the screen it happens to be shown on bounds
        // neither. A `Box` here handed each card the viewport as its maximum, and
        // an over-large child is not merely clamped: `Placeable.width` is the
        // coerced width, and `place` then adds `apparentToRealOffset`, which
        // *centres* the real content on it. A node wider than the screen therefore
        // drew itself — and the port handles it carries — half its overflow to the
        // left of where it actually is, while the wires, drawn straight from the
        // graph coordinates by the layer below, stayed where the ports belong.
        // `action.break` over a twelve-field struct is what first reaches that
        // width, at 418dp against a 411dp screen.
        val placeables = measurables.map { it.measure(Constraints()) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEach { it.place(0, 0) }
        }
    }
}

private fun DrawScope.drawGrid(transform: CanvasTransform) {
    var spacing = GRID_SPACING * density * transform.scale
    while (spacing < MIN_GRID_SPACING_PX) spacing *= 2f
    val startX = ((transform.offset.x % spacing) + spacing) % spacing
    val startY = ((transform.offset.y % spacing) + spacing) % spacing
    val points = mutableListOf<Offset>()
    var x = startX - spacing
    while (x <= size.width) {
        var y = startY - spacing
        while (y <= size.height) {
            points.add(Offset(x, y))
            y += spacing
        }
        x += spacing
    }
    val dotSize = (GRID_DOT_BASE * transform.scale).coerceIn(GRID_DOT_MIN, GRID_DOT_MAX)
    drawPoints(
        points = points,
        pointMode = PointMode.Points,
        color = EditorColors.gridDot,
        strokeWidth = dotSize,
        cap = StrokeCap.Round,
    )
}

/**
 * [blocked] is the set of edges the executor will refuse to follow. They keep the
 * dash pattern that says which channel they belong to — that is load-bearing — and
 * change only colour, so a blocked data wire still reads as a data wire.
 */
private fun DrawScope.drawConnections(state: GraphEditorUiState, blocked: Set<String>) {
    val workflow = state.workflow
    val selection = state.selection
    workflow.execConnections.forEach { connection ->
        val from = execRef(connection.fromNodeId, connection.fromPort, isOutput = true)
        val to = execRef(connection.toNodeId, connection.toPort, isOutput = false)
        val start = portPositionOf(workflow, from) ?: return@forEach
        val end = portPositionOf(workflow, to) ?: return@forEach
        val isSelected = connection.id in selection
        val color = when {
            isSelected -> EditorColors.execEdgeSelected
            connection.id in blocked -> EditorColors.errorAccent
            else -> EditorColors.execEdge
        }
        drawWire(start, end, color, isSelected, dashed = false)
    }
    workflow.dataConnections.forEach { connection ->
        val from = dataRef(connection.fromNodeId, connection.fromPort, isOutput = true)
        val to = dataRef(connection.toNodeId, connection.toPort, isOutput = false)
        val start = portPositionOf(workflow, from) ?: return@forEach
        val end = portPositionOf(workflow, to) ?: return@forEach
        val isSelected = connection.id in selection
        val color = when {
            isSelected -> EditorColors.dataEdgeSelected
            connection.id in blocked -> EditorColors.errorAccent
            else -> portTypeColor(resolvePort(workflow, from)?.schema)
        }
        drawWire(start, end, color, isSelected, dashed = true)
    }
}

private fun DrawScope.drawWire(start: Offset, end: Offset, color: Color, isSelected: Boolean, dashed: Boolean) {
    val width = if (isSelected) EDGE_SELECTED_WIDTH else EDGE_WIDTH
    drawEdge(start, end, color, width, dashed)
    drawArrow(start, end, color)
}

private fun edgeHitTest(workflow: Workflow, graphPos: Offset): String? {
    var bestId: String? = null
    var bestDist = GraphGeometry.EDGE_HIT_RADIUS
    workflow.execConnections.forEach { connection ->
        val from = execRef(connection.fromNodeId, connection.fromPort, isOutput = true)
        val to = execRef(connection.toNodeId, connection.toPort, isOutput = false)
        val start = portPositionOf(workflow, from) ?: return@forEach
        val end = portPositionOf(workflow, to) ?: return@forEach
        val d = GraphGeometry.distanceToEdge(start, end, graphPos)
        if (d < bestDist) { bestDist = d; bestId = connection.id }
    }
    workflow.dataConnections.forEach { connection ->
        val from = dataRef(connection.fromNodeId, connection.fromPort, isOutput = true)
        val to = dataRef(connection.toNodeId, connection.toPort, isOutput = false)
        val start = portPositionOf(workflow, from) ?: return@forEach
        val end = portPositionOf(workflow, to) ?: return@forEach
        val d = GraphGeometry.distanceToEdge(start, end, graphPos)
        if (d < bestDist) { bestDist = d; bestId = connection.id }
    }
    return bestId
}

private fun DrawScope.drawEdge(start: Offset, end: Offset, color: Color, width: Float, dashed: Boolean = false) {
    val reach = GraphGeometry.bezierReach(start, end)
    val path = Path().apply {
        moveTo(start.x, start.y)
        cubicTo(start.x, start.y + reach, end.x, end.y - reach, end.x, end.y)
    }
    val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(DASH_ON, DASH_OFF)) else null
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, pathEffect = effect))
    drawCircle(color, ENDPOINT_RADIUS, start)
    drawCircle(color, ENDPOINT_RADIUS, end)
}

private fun DrawScope.drawArrow(start: Offset, end: Offset, color: Color) {
    val mid = GraphGeometry.edgePoint(start, end, ARROW_MID_T)
    val ahead = GraphGeometry.edgePoint(start, end, ARROW_AHEAD_T)
    val direction = ahead - mid
    val length = direction.getDistance()
    if (length < MIN_ARROW_DIRECTION) return
    val dir = direction / length
    val perp = Offset(-dir.y, dir.x)
    val back = ARROW_SIZE * ARROW_BACK_RATIO
    val side = ARROW_SIZE * ARROW_WIDTH_RATIO
    val path = Path().apply {
        moveTo(mid.x + dir.x * ARROW_SIZE, mid.y + dir.y * ARROW_SIZE)
        lineTo(mid.x - dir.x * back + perp.x * side, mid.y - dir.y * back + perp.y * side)
        lineTo(mid.x - dir.x * back - perp.x * side, mid.y - dir.y * back - perp.y * side)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawPendingConnection(state: GraphEditorUiState) {
    // While the drop-to-add palette is open the drag is over, but the edge stays
    // on screen so the user can see what they are about to connect.
    val pending = state.pendingConnection
        ?: state.nodePick?.let { PendingConnection(it.from, it.dropPosGraph) }
        ?: return
    val fromPos = portPositionOf(state.workflow, pending.from) ?: return
    val target = pending.hoverPort?.let { portPositionOf(state.workflow, it) } ?: pending.currentPos
    val (start, end) = if (pending.from.isOutput) fromPos to target else target to fromPos
    val color = if (pending.from.kind == PortKind.DATA) {
        portTypeColor(resolvePort(state.workflow, pending.from)?.schema)
    } else {
        EditorColors.execEdge
    }
    drawEdge(start, end, color, EDGE_WIDTH, dashed = true)
    if (pending.hoverPort != null) {
        drawCircle(EditorColors.portSnap.copy(alpha = SNAP_HIGHLIGHT_ALPHA), SNAP_HIGHLIGHT_RADIUS, target)
    }
}

internal fun portPositionOf(workflow: Workflow, ref: PortRef): Offset? =
    resolvePort(workflow, ref)?.let { port ->
        workflow.node(ref.nodeId)?.let { node ->
            NodeTypeRegistry.byId(node.typeId)?.let { definition ->
                val inputPorts = effectiveInputPorts(definition, workflow, node)
                val outputPorts = effectiveOutputPorts(definition, workflow, node)
                val width = GraphGeometry.nodeWidth(node.typeId, inputPorts.size, outputPorts.size)
                GraphGeometry.portPosition(node, inputPorts, outputPorts, width, port)
            }
        }
    }

/**
 * Resolves the placed [Port] for [ref] (honouring dynamic + exposed ports).
 * Used to read a port's [ItemSchema] for type-coloured data edges and for
 * compose-time schema subtyping checks.
 */
internal fun resolvePort(workflow: Workflow, ref: PortRef): Port? =
    workflow.node(ref.nodeId)?.let { node ->
        NodeTypeRegistry.byId(node.typeId)?.let { definition ->
            val ports = if (ref.isOutput) {
                effectiveOutputPorts(definition, workflow, node)
            } else {
                effectiveInputPorts(definition, workflow, node)
            }
            ports.firstOrNull { it.name == ref.portName && it.kind == ref.kind }
        }
    }

private fun execRef(nodeId: NodeId, portName: PortName, isOutput: Boolean): PortRef =
    PortRef(nodeId, portName, isOutput, PortKind.EXECUTION)

private fun dataRef(nodeId: NodeId, portName: PortName, isOutput: Boolean): PortRef =
    PortRef(nodeId, portName, isOutput, PortKind.DATA)
