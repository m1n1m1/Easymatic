package com.example.ottomatic.feature.grapheditor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.ottomatic.data.WorkflowRepository
import com.example.ottomatic.domain.model.Connection
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.registry.NodeTypeRegistry
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Pan/zoom state of the canvas. Offset is in screen px, scale is unitless. */
data class CanvasTransform(
    val offset: Offset = Offset.Zero,
    val scale: Float = 1f,
)

/** Reference to a single port on a node. */
data class PortRef(
    val nodeId: String,
    val portIndex: Int,
    val isOutput: Boolean,
)

/** An in-progress connection drag from a port to the current pointer position. */
data class PendingConnection(
    val from: PortRef,
    val currentPos: Offset,
    val hoverPort: PortRef? = null,
)

sealed interface Selection {
    data class Node(val nodeId: String) : Selection
    data class Edge(val connectionId: String) : Selection
}

data class GraphEditorUiState(
    val workflow: Workflow = Workflow(),
    val transform: CanvasTransform = CanvasTransform(),
    val selection: Selection? = null,
    val pendingConnection: PendingConnection? = null,
    val isLoaded: Boolean = false,
)

@Suppress("TooManyFunctions") // Editor surface: transform, selection, node and connection editing.
class GraphEditorViewModel(
    private val repository: WorkflowRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GraphEditorUiState())
    val uiState: StateFlow<GraphEditorUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val workflow = repository.load() ?: sampleWorkflow()
            _uiState.update { it.copy(workflow = workflow, isLoaded = true) }
        }
    }

    // region Canvas transform

    fun onPan(deltaPx: Offset) {
        _uiState.update { state ->
            state.copy(transform = state.transform.copy(offset = state.transform.offset + deltaPx))
        }
    }

    fun onZoom(centroidPx: Offset, zoomChange: Float, panPx: Offset) {
        _uiState.update { state ->
            val old = state.transform
            val newScale = (old.scale * zoomChange).coerceIn(GraphGeometry.MIN_ZOOM, GraphGeometry.MAX_ZOOM)
            val factor = newScale / old.scale
            val newOffset = centroidPx - (centroidPx - old.offset) * factor + panPx
            state.copy(transform = CanvasTransform(offset = newOffset, scale = newScale))
        }
    }

    fun zoomBy(factor: Float, viewportCenterPx: Offset) {
        onZoom(viewportCenterPx, factor, Offset.Zero)
    }

    fun fitToContent(viewportSizePx: Size, density: Float) {
        val nodes = _uiState.value.workflow.nodes
        if (nodes.isEmpty() || viewportSizePx.minDimension <= 0f) {
            _uiState.update { it.copy(transform = CanvasTransform()) }
            return
        }
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (node in nodes) {
            val def = NodeTypeRegistry.byId(node.typeId) ?: continue
            minX = min(minX, node.x)
            minY = min(minY, node.y)
            maxX = max(maxX, node.x + GraphGeometry.nodeWidth(def))
            maxY = max(maxY, node.y + GraphGeometry.NODE_HEIGHT)
        }
        val padding = FIT_PADDING
        minX -= padding
        minY -= padding
        maxX += padding
        maxY += padding
        val contentWidthPx = (maxX - minX) * density
        val contentHeightPx = (maxY - minY) * density
        val scale = min(viewportSizePx.width / contentWidthPx, viewportSizePx.height / contentHeightPx)
            .coerceIn(GraphGeometry.MIN_ZOOM, MAX_FIT_ZOOM)
        val offsetX = (viewportSizePx.width - contentWidthPx * scale) / 2f - minX * density * scale
        val offsetY = (viewportSizePx.height - contentHeightPx * scale) / 2f - minY * density * scale
        _uiState.update { it.copy(transform = CanvasTransform(Offset(offsetX, offsetY), scale)) }
    }

    // endregion

    // region Selection

    fun selectNode(nodeId: String) {
        _uiState.update { it.copy(selection = Selection.Node(nodeId)) }
    }

    fun selectConnection(connectionId: String) {
        _uiState.update { it.copy(selection = Selection.Edge(connectionId)) }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selection = null) }
    }

    fun deleteSelection() {
        val selection = _uiState.value.selection ?: return
        _uiState.update { state ->
            val workflow = state.workflow
            val updated = when (selection) {
                is Selection.Node -> workflow.copy(
                    nodes = workflow.nodes.filterNot { it.id == selection.nodeId },
                    connections = workflow.connections.filterNot {
                        it.fromNodeId == selection.nodeId || it.toNodeId == selection.nodeId
                    },
                )

                is Selection.Edge -> workflow.copy(
                    connections = workflow.connections.filterNot { it.id == selection.connectionId },
                )
            }
            state.copy(workflow = updated, selection = null)
        }
        persist()
    }

    // endregion

    // region Node editing

    fun addNode(typeId: String, positionGraph: Offset) {
        val definition = NodeTypeRegistry.byId(typeId) ?: return
        val node = WorkflowNode(
            id = UUID.randomUUID().toString(),
            typeId = typeId,
            name = definition.displayName,
            x = positionGraph.x,
            y = positionGraph.y,
        )
        _uiState.update { state ->
            state.copy(
                workflow = state.workflow.copy(nodes = state.workflow.nodes + node),
                selection = Selection.Node(node.id),
            )
        }
        persist()
    }

    fun moveNode(nodeId: String, deltaGraph: Offset) {
        _uiState.update { state ->
            val nodes = state.workflow.nodes.map { node ->
                if (node.id == nodeId) node.copy(x = node.x + deltaGraph.x, y = node.y + deltaGraph.y) else node
            }
            state.copy(workflow = state.workflow.copy(nodes = nodes))
        }
    }

    fun onNodeDragEnd() {
        persist()
    }

    // endregion

    // region Connection dragging

    fun startPortDrag(from: PortRef) {
        val position = portPosition(from) ?: return
        _uiState.update { it.copy(pendingConnection = PendingConnection(from = from, currentPos = position)) }
    }

    fun updatePortDrag(deltaGraph: Offset) {
        _uiState.update { state ->
            val pending = state.pendingConnection ?: return@update state
            val pos = pending.currentPos + deltaGraph
            state.copy(pendingConnection = pending.copy(currentPos = pos, hoverPort = findSnapPort(pending.from, pos)))
        }
    }

    fun endPortDrag() {
        val pending = _uiState.value.pendingConnection
        _uiState.update { it.copy(pendingConnection = null) }
        val target = pending?.hoverPort ?: return
        val (output, input) = if (pending.from.isOutput) pending.from to target else target to pending.from
        val exists = _uiState.value.workflow.connections.any {
            it.fromNodeId == output.nodeId && it.fromPortIndex == output.portIndex &&
                it.toNodeId == input.nodeId && it.toPortIndex == input.portIndex
        }
        if (exists) return
        val connection = Connection(
            id = UUID.randomUUID().toString(),
            fromNodeId = output.nodeId,
            fromPortIndex = output.portIndex,
            toNodeId = input.nodeId,
            toPortIndex = input.portIndex,
        )
        _uiState.update { state ->
            state.copy(workflow = state.workflow.copy(connections = state.workflow.connections + connection))
        }
        persist()
    }

    fun cancelPortDrag() {
        _uiState.update { it.copy(pendingConnection = null) }
    }

    // endregion

    /** Absolute graph position of a port, or null if the node/type is unknown. */
    fun portPosition(ref: PortRef): Offset? {
        val node = _uiState.value.workflow.node(ref.nodeId)
        val definition = node?.let { NodeTypeRegistry.byId(it.typeId) }
        return if (node != null && definition != null) {
            GraphGeometry.portPosition(node, definition, ref.portIndex, ref.isOutput)
        } else {
            null
        }
    }

    private fun findSnapPort(from: PortRef, positionGraph: Offset): PortRef? {
        val workflow = _uiState.value.workflow
        var best: PortRef? = null
        var bestDistance = GraphGeometry.PORT_SNAP_RADIUS
        val wantOutput = !from.isOutput
        workflow.nodes
            .filter { it.id != from.nodeId }
            .forEach { node ->
                val definition = NodeTypeRegistry.byId(node.typeId) ?: return@forEach
                val ports = if (wantOutput) definition.outputPorts else definition.inputPorts
                for (index in ports.indices) {
                    val portPos = GraphGeometry.portPosition(node, definition, index, wantOutput)
                    val distance = (portPos - positionGraph).getDistance()
                    if (distance < bestDistance) {
                        bestDistance = distance
                        best = PortRef(node.id, index, wantOutput)
                    }
                }
            }
        return best
    }

    private fun persist() {
        val workflow = _uiState.value.workflow
        viewModelScope.launch { repository.save(workflow) }
    }

    @Suppress("MagicNumber") // Hand-tuned demo layout coordinates.
    private fun sampleWorkflow(): Workflow = Workflow(
        nodes = listOf(
            WorkflowNode("n1", "trigger.manual", "Manual Trigger", 250f, 60f),
            WorkflowNode("n2", "action.http", "HTTP Request", 250f, 220f),
            WorkflowNode("n3", "action.condition", "If / Condition", 242f, 380f),
            WorkflowNode("n4", "action.notify", "Show Notification", 90f, 540f),
            WorkflowNode("n5", "action.delay", "Wait", 410f, 540f),
        ),
        connections = listOf(
            Connection("c1", "n1", 0, "n2", 0),
            Connection("c2", "n2", 0, "n3", 0),
            Connection("c3", "n3", 0, "n4", 0),
            Connection("c4", "n3", 1, "n5", 0),
        ),
    )

    companion object {
        private const val FIT_PADDING = 48f
        private const val MAX_FIT_ZOOM = 1.25f

        fun factory(repository: WorkflowRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { GraphEditorViewModel(repository) }
        }
    }
}
