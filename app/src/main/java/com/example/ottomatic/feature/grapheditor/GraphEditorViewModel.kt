package com.example.ottomatic.feature.grapheditor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.ottomatic.data.WorkflowRepository
import com.example.ottomatic.domain.model.DataConnection
import com.example.ottomatic.domain.model.ExecConnection
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.registry.NodeTypeRegistry
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.WorkflowRunner
import com.example.ottomatic.engine.trigger.ManualTrigger
import com.example.ottomatic.engine.trigger.TriggerHost
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Job
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

/** Reference to a single port on a node, addressed by name and kind. */
data class PortRef(
    val nodeId: String,
    val portName: String,
    val isOutput: Boolean,
    val kind: PortKind,
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
    val isRunning: Boolean = false,
)

@Suppress("TooManyFunctions") // Editor surface: transform, selection, node and connection editing.
class GraphEditorViewModel(
    private val repository: WorkflowRepository,
    private val triggerHost: TriggerHost,
    private val executionContext: ExecutionContext,
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
                    execConnections = workflow.execConnections.filterNot {
                        it.fromNodeId == selection.nodeId || it.toNodeId == selection.nodeId
                    },
                    dataConnections = workflow.dataConnections.filterNot {
                        it.fromNodeId == selection.nodeId || it.toNodeId == selection.nodeId
                    },
                )

                is Selection.Edge -> workflow.copy(
                    execConnections = workflow.execConnections.filterNot { it.id == selection.connectionId },
                    dataConnections = workflow.dataConnections.filterNot { it.id == selection.connectionId },
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
        require(output.kind == input.kind) { "Cannot connect exec port to data port" }
        val alreadyExists = when (output.kind) {
            PortKind.EXECUTION -> _uiState.value.workflow.execConnections.any {
                it.fromNodeId == output.nodeId && it.fromPort == output.portName &&
                    it.toNodeId == input.nodeId && it.toPort == input.portName
            }
            PortKind.DATA -> _uiState.value.workflow.dataConnections.any {
                it.fromNodeId == output.nodeId && it.fromPort == output.portName &&
                    it.toNodeId == input.nodeId && it.toPort == input.portName
            }
        }
        if (alreadyExists) return
        _uiState.update { state ->
            val workflow = state.workflow
            val updated = when (output.kind) {
                PortKind.EXECUTION -> workflow.copy(
                    execConnections = workflow.execConnections + ExecConnection(
                        id = UUID.randomUUID().toString(),
                        fromNodeId = output.nodeId,
                        fromPort = output.portName,
                        toNodeId = input.nodeId,
                        toPort = input.portName,
                    ),
                )
                PortKind.DATA -> workflow.copy(
                    dataConnections = workflow.dataConnections + DataConnection(
                        id = UUID.randomUUID().toString(),
                        fromNodeId = output.nodeId,
                        fromPort = output.portName,
                        toNodeId = input.nodeId,
                        toPort = input.portName,
                    ),
                )
            }
            state.copy(workflow = updated)
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
        val port = definition?.port(ref.portName)?.takeIf { it.kind == ref.kind }
        return if (node != null && definition != null && port != null) {
            GraphGeometry.portPosition(node, definition, port)
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
                for (port in ports) {
                    if (port.kind != from.kind) continue
                    val portPos = GraphGeometry.portPosition(node, definition, port)
                    val distance = (portPos - positionGraph).getDistance()
                    if (distance < bestDistance) {
                        bestDistance = distance
                        best = PortRef(node.id, port.name, wantOutput, port.kind)
                    }
                }
            }
        return best
    }

    private fun persist() {
        val workflow = _uiState.value.workflow
        viewModelScope.launch { repository.save(workflow) }
    }

    // region Workflow execution

    private var runJob: Job? = null

    fun runWorkflow() {
        if (_uiState.value.isRunning) return
        val workflow = _uiState.value.workflow
        val firstTrigger = workflow.nodes.firstOrNull {
            NodeTypeRegistry.byId(it.typeId)?.kind == NodeKind.TRIGGER
        } ?: return
        val runner = WorkflowRunner(triggerHost, executionContext)
        _uiState.update { it.copy(isRunning = true) }
        runJob = runner.run(viewModelScope, workflow)
        viewModelScope.launch {
            runJob?.join()
            _uiState.update { it.copy(isRunning = false) }
        }
        if (firstTrigger.typeId == ManualTrigger.TYPE_ID) {
            ManualTrigger.fire(firstTrigger.id)
        }
    }

    fun stopWorkflow() {
        runJob?.cancel()
        runJob = null
        _uiState.value.workflow.nodes
            .filter { it.typeId == ManualTrigger.TYPE_ID }
            .forEach { ManualTrigger.release(it.id) }
        _uiState.update { it.copy(isRunning = false) }
    }

    // endregion

    // region Node configuration

    fun updateNodeConfig(nodeId: String, key: String, value: String) {
        _uiState.update { state ->
            val nodes = state.workflow.nodes.map { node ->
                if (node.id == nodeId) node.copy(config = node.config + (key to value)) else node
            }
            state.copy(workflow = state.workflow.copy(nodes = nodes))
        }
        persist()
    }

    fun updateNodeName(nodeId: String, name: String) {
        _uiState.update { state ->
            val nodes = state.workflow.nodes.map { node ->
                if (node.id == nodeId) node.copy(name = name) else node
            }
            state.copy(workflow = state.workflow.copy(nodes = nodes))
        }
        persist()
    }

    // endregion

    @Suppress("MagicNumber") // Hand-tuned demo layout coordinates.
    private fun sampleWorkflow(): Workflow = Workflow(
        nodes = listOf(
            WorkflowNode("n1", "trigger.manual", "Manual Trigger", 250f, 60f),
            WorkflowNode("n2", "action.http", "HTTP Request", 250f, 220f),
            WorkflowNode("n3", "action.condition", "If / Condition", 242f, 380f),
            WorkflowNode("n4", "action.notify", "Show Notification", 90f, 540f),
            WorkflowNode("n5", "action.delay", "Wait", 410f, 540f),
        ),
        execConnections = listOf(
            ExecConnection("c1", "n1", "out", "n2", "in"),
            ExecConnection("c2", "n2", "out", "n3", "in"),
            ExecConnection("c3", "n3", "true", "n4", "in"),
            ExecConnection("c4", "n3", "false", "n5", "in"),
        ),
    )

    companion object {
        private const val FIT_PADDING = 48f
        private const val MAX_FIT_ZOOM = 1.25f

        fun factory(
            repository: WorkflowRepository,
            triggerHost: TriggerHost,
            executionContext: ExecutionContext,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { GraphEditorViewModel(repository, triggerHost, executionContext) }
        }
    }
}
