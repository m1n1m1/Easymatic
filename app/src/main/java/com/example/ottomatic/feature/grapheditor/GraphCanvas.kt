package com.example.ottomatic.feature.grapheditor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.registry.NodeTypeRegistry

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

@Composable
fun GraphCanvas(
    state: GraphEditorUiState,
    viewModel: GraphEditorViewModel,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clipToBounds()
            .background(EditorColors.canvasBackground),
    ) {
        BackgroundLayer(state, viewModel)
        NodeLayer(state, viewModel)
    }
}

@Composable
private fun BackgroundLayer(state: GraphEditorUiState, viewModel: GraphEditorViewModel) {
    val transform = state.transform
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(viewModel) {
                detectTapGestures { positionPx ->
                    val current = viewModel.uiState.value
                    val t = current.transform
                    val graphPos = (positionPx - t.offset) / (t.scale * density)
                    val hit = current.workflow.connections
                        .mapNotNull { connection ->
                            val start = portPositionOf(
                                current.workflow,
                                PortRef(connection.fromNodeId, connection.fromPortIndex, true),
                            )
                            val end = portPositionOf(
                                current.workflow,
                                PortRef(connection.toNodeId, connection.toPortIndex, false),
                            )
                            if (start == null || end == null) return@mapNotNull null
                            connection.id to GraphGeometry.distanceToEdge(start, end, graphPos)
                        }
                        .filter { it.second <= GraphGeometry.EDGE_HIT_RADIUS }
                        .minByOrNull { it.second }
                    if (hit != null) viewModel.selectConnection(hit.first) else viewModel.clearSelection()
                }
            }
            .pointerInput(viewModel) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    if (zoom != 1f) {
                        viewModel.onZoom(centroid, zoom, pan)
                    } else {
                        viewModel.onPan(pan)
                    }
                }
            },
    ) {
        drawGrid(transform)
        val densityScale = transform.scale * density
        withTransform({
            translate(transform.offset.x, transform.offset.y)
            scale(densityScale, densityScale, pivot = Offset.Zero)
        }) {
            drawConnections(state)
            drawPendingConnection(state)
        }
    }
}

@Composable
private fun NodeLayer(state: GraphEditorUiState, viewModel: GraphEditorViewModel) {
    val transform = state.transform
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = transform.offset.x
                translationY = transform.offset.y
                scaleX = transform.scale
                scaleY = transform.scale
                transformOrigin = TransformOrigin(0f, 0f)
            },
    ) {
        val selectedNodeId = (state.selection as? Selection.Node)?.nodeId
        state.workflow.nodes.forEach { node ->
            val definition = NodeTypeRegistry.byId(node.typeId) ?: return@forEach
            key(node.id) {
                NodeCard(
                    node = node,
                    definition = definition,
                    isSelected = node.id == selectedNodeId,
                    hoverPort = state.pendingConnection?.hoverPort,
                    onSelect = { viewModel.selectNode(node.id) },
                    onDrag = { delta -> viewModel.moveNode(node.id, delta) },
                    onDragEnd = { viewModel.onNodeDragEnd() },
                    onPortDragStart = { ref -> viewModel.startPortDrag(ref) },
                    onPortDrag = { delta -> viewModel.updatePortDrag(delta) },
                    onPortDragEnd = { viewModel.endPortDrag() },
                    onPortDragCancel = { viewModel.cancelPortDrag() },
                )
            }
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

private fun DrawScope.drawConnections(state: GraphEditorUiState) {
    val workflow = state.workflow
    val selectedEdgeId = (state.selection as? Selection.Edge)?.connectionId
    workflow.connections.forEach { connection ->
        val start = portPositionOf(
            workflow,
            PortRef(connection.fromNodeId, connection.fromPortIndex, true),
        ) ?: return@forEach
        val end = portPositionOf(
            workflow,
            PortRef(connection.toNodeId, connection.toPortIndex, false),
        ) ?: return@forEach
        val isSelected = connection.id == selectedEdgeId
        val color = if (isSelected) EditorColors.edgeSelected else EditorColors.edge
        val width = if (isSelected) EDGE_SELECTED_WIDTH else EDGE_WIDTH
        drawEdge(start, end, color, width)
        drawArrow(start, end, color)
    }
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
    val pending = state.pendingConnection ?: return
    val fromPos = portPositionOf(state.workflow, pending.from) ?: return
    val target = pending.hoverPort?.let { portPositionOf(state.workflow, it) } ?: pending.currentPos
    val (start, end) = if (pending.from.isOutput) fromPos to target else target to fromPos
    drawEdge(start, end, EditorColors.edgePending, EDGE_WIDTH, dashed = true)
    if (pending.hoverPort != null) {
        drawCircle(EditorColors.portSnap.copy(alpha = SNAP_HIGHLIGHT_ALPHA), SNAP_HIGHLIGHT_RADIUS, target)
    }
}

internal fun portPositionOf(workflow: Workflow, ref: PortRef): Offset? {
    val node = workflow.node(ref.nodeId)
    val definition = node?.let { NodeTypeRegistry.byId(it.typeId) }
    return if (node != null && definition != null) {
        GraphGeometry.portPosition(node, definition, ref.portIndex, ref.isOutput)
    } else {
        null
    }
}
