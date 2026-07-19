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
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.domain.registry.CONDITION_SOURCE_IN
import com.example.ottomatic.domain.registry.CONDITION_TYPE_CONFIG_KEY
import com.example.ottomatic.domain.registry.CONDITION_TYPE_ID
import com.example.ottomatic.domain.registry.NodeTypeRegistry
import com.example.ottomatic.domain.registry.effectiveInputPorts
import com.example.ottomatic.domain.registry.effectiveOutputPorts
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
        val workflow = _uiState.value.workflow
        val nodes = workflow.nodes
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
            val inputPorts = effectiveInputPorts(def, workflow, node)
            val outputPorts = effectiveOutputPorts(def, workflow, node)
            minX = min(minX, node.x)
            minY = min(minY, node.y)
            maxX = max(maxX, node.x + GraphGeometry.nodeWidth(inputPorts.size, outputPorts.size))
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
        // The condition's `source` input is exposable; default it to exposed so
        // a freshly placed condition already shows its data input port.
        val defaultExposed = if (typeId == CONDITION_TYPE_ID) setOf(CONDITION_SOURCE_IN) else emptySet()
        val node = WorkflowNode(
            id = UUID.randomUUID().toString(),
            typeId = typeId,
            name = definition.displayName,
            x = positionGraph.x,
            y = positionGraph.y,
            exposedInputs = defaultExposed,
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
        val workflow = _uiState.value.workflow
        if (output.kind == PortKind.DATA && !isTypeCompatible(workflow, output, input)) return
        if (!connectionExists(workflow, output, input)) addConnection(output, input)
    }

    /**
     * Compose-time schema subtyping check for a candidate DATA edge
     * [output] → [input], mirroring [GraphValidator] so incompatible edges are
     * silently rejected at drop time (Blueprint-style). Wildcard ports (e.g.
     * `action.break`'s `struct` input) accept anything.
     */
    private fun isTypeCompatible(workflow: Workflow, output: PortRef, input: PortRef): Boolean {
        val sourceSchema = resolvePort(workflow, output)?.schema ?: ItemSchema.Wildcard
        val targetSchema = resolvePort(workflow, input)?.schema ?: ItemSchema.Wildcard
        return targetSchema.isAssignableFrom(sourceSchema)
    }

    private fun connectionExists(workflow: Workflow, output: PortRef, input: PortRef): Boolean =
        when (output.kind) {
            PortKind.EXECUTION -> workflow.execConnections.any {
                it.fromNodeId == output.nodeId && it.fromPort == output.portName &&
                    it.toNodeId == input.nodeId && it.toPort == input.portName
            }
            PortKind.DATA -> workflow.dataConnections.any {
                it.fromNodeId == output.nodeId && it.fromPort == output.portName &&
                    it.toNodeId == input.nodeId && it.toPort == input.portName
            }
        }

    private fun addConnection(output: PortRef, input: PortRef) {
        _uiState.update { state ->
            val wf = state.workflow
            val updated = when (output.kind) {
                PortKind.EXECUTION -> wf.copy(
                    execConnections = wf.execConnections + ExecConnection(
                        id = UUID.randomUUID().toString(),
                        fromNodeId = output.nodeId,
                        fromPort = output.portName,
                        toNodeId = input.nodeId,
                        toPort = input.portName,
                    ),
                )
                PortKind.DATA -> wf.copy(
                    dataConnections = wf.dataConnections + DataConnection(
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
        val workflow = _uiState.value.workflow
        return workflow.node(ref.nodeId)?.let { node ->
            NodeTypeRegistry.byId(node.typeId)?.let { definition ->
                val inputPorts = effectiveInputPorts(definition, workflow, node)
                val outputPorts = effectiveOutputPorts(definition, workflow, node)
                val width = GraphGeometry.nodeWidth(inputPorts.size, outputPorts.size)
                (if (ref.isOutput) outputPorts else inputPorts)
                    .firstOrNull { it.name == ref.portName && it.kind == ref.kind }
                    ?.let { GraphGeometry.portPosition(node, inputPorts, outputPorts, width, it) }
            }
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
                val ports = if (wantOutput) {
                    effectiveOutputPorts(definition, workflow, node)
                } else {
                    effectiveInputPorts(definition, workflow, node)
                }
                for (port in ports) {
                    if (port.kind != from.kind) continue
                    val inputPorts = effectiveInputPorts(definition, workflow, node)
                    val outputPorts = effectiveOutputPorts(definition, workflow, node)
                    val width = GraphGeometry.nodeWidth(inputPorts.size, outputPorts.size)
                    val portPos = GraphGeometry.portPosition(node, inputPorts, outputPorts, width, port)
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
            val workflow = state.workflow.copy(nodes = nodes)
            // When the condition's type chooser changes, drop any data edge wired into its
            // `source` port: the port's schema is about to change and the old connection
            // would likely fail the new type check.
            val finalWorkflow = if (key == CONDITION_TYPE_CONFIG_KEY) {
                workflow.copy(
                    dataConnections = workflow.dataConnections.filterNot {
                        it.toNodeId == nodeId && it.toPort == CONDITION_SOURCE_IN
                    },
                )
            } else {
                workflow
            }
            state.copy(workflow = finalWorkflow)
        }
        persist()
    }

    /**
     * Toggles whether the config field [fieldKey] on [nodeId] is exposed as a
     * typed DATA input port (see [WorkflowNode.exposedInputs]). When exposed,
     * the node gains a DATA IN port named [fieldKey]; an incoming edge's item
     * overrides the static form value for that field at runtime.
     */
    fun toggleNodeExposedInput(nodeId: String, fieldKey: String) {
        _uiState.update { state ->
            val nodes = state.workflow.nodes.map { node ->
                if (node.id != nodeId) {
                    node
                } else {
                    val next = if (fieldKey in node.exposedInputs) {
                        node.exposedInputs - fieldKey
                    } else {
                        node.exposedInputs + fieldKey
                    }
                    node.copy(exposedInputs = next)
                }
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
