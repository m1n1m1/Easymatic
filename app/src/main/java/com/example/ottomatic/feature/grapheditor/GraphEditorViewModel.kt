package com.example.ottomatic.feature.grapheditor

import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.ConfigKey
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.ottomatic.data.WorkflowRepository
import com.example.ottomatic.domain.model.AttachedCondition
import com.example.ottomatic.domain.model.ConditionLogic
import com.example.ottomatic.domain.model.ValueSource
import com.example.ottomatic.domain.model.DataConnection
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.ExecConnection
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.registry.IF_SOURCE_IN
import com.example.ottomatic.domain.registry.IF_SOURCE_KEY
import com.example.ottomatic.domain.registry.IF_TYPE_CONFIG_KEY
import com.example.ottomatic.domain.registry.IF_TYPE_ID
import com.example.ottomatic.domain.registry.IF_VALUE_IN
import com.example.ottomatic.domain.registry.DragOrigin
import com.example.ottomatic.domain.registry.NodeSuggestion
import com.example.ottomatic.domain.registry.NodeTypeRegistry
import com.example.ottomatic.domain.registry.effectiveInputPorts
import com.example.ottomatic.domain.registry.effectiveOutputPorts
import com.example.ottomatic.domain.registry.isDataAssignable
import com.example.ottomatic.domain.registry.suggestionsFor
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
    val nodeId: NodeId,
    val portName: PortName,
    val isOutput: Boolean,
    val kind: PortKind,
)

/** An in-progress connection drag from a port to the current pointer position. */
data class PendingConnection(
    val from: PortRef,
    val currentPos: Offset,
    val hoverPort: PortRef? = null,
)

/**
 * A connection drag released on empty canvas, awaiting a node type from the
 * palette. Holds everything needed to place the new node at the drop point and
 * wire it in one step (Blueprint-style "drag off a pin").
 */
data class NodePickRequest(
    val from: PortRef,
    val dropPosGraph: Offset,
    val suggestions: List<NodeSuggestion>,
)

sealed interface Selection {
    data class Node(val nodeId: NodeId) : Selection
    data class Edge(val connectionId: String) : Selection
}

data class GraphEditorUiState(
    val workflow: Workflow = Workflow(),
    val transform: CanvasTransform = CanvasTransform(),
    val selection: Selection? = null,
    val pendingConnection: PendingConnection? = null,
    val nodePick: NodePickRequest? = null,
    val revealedLabel: PortRef? = null,
    val isLoaded: Boolean = false,
    val isRunning: Boolean = false,
    val isMacroEnabled: Boolean = false,
)

@Suppress("TooManyFunctions") // Editor surface: transform, selection, node and connection editing.
class GraphEditorViewModel(
    private val repository: WorkflowRepository,
    private val triggerHost: TriggerHost,
    private val executionContext: ExecutionContext,
    private val appContext: android.content.Context,
    private val workflowId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GraphEditorUiState())
    val uiState: StateFlow<GraphEditorUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val workflow = repository.load(workflowId) ?: Workflow(id = workflowId)
            _uiState.update {
                it.copy(workflow = workflow, isLoaded = true, isMacroEnabled = workflow.enabled)
            }
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

    fun selectNode(nodeId: NodeId) {
        _uiState.update { it.copy(selection = Selection.Node(nodeId)) }
    }

    fun selectConnection(connectionId: String) {
        _uiState.update { it.copy(selection = Selection.Edge(connectionId)) }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selection = null) }
    }

    /**
     * Toggles whether the label of port [ref] is revealed on the canvas. Only
     * one port label is shown at a time: tapping a different port swaps the
     * revealed label, tapping the same port again hides it. Mobile-friendly
     * replacement for hover-to-reveal (no hover on touch screens).
     */
    fun toggleRevealedLabel(ref: PortRef) {
        _uiState.update { state ->
            state.copy(revealedLabel = if (state.revealedLabel == ref) null else ref)
        }
    }

    fun clearRevealedLabel() {
        _uiState.update { it.copy(revealedLabel = null) }
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

    fun addNode(typeId: NodeTypeId, positionGraph: Offset) {
        val definition = NodeTypeRegistry.byId(typeId) ?: return
        val node = WorkflowNode(
            id = NodeId(UUID.randomUUID().toString()),
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

    fun moveNode(nodeId: NodeId, deltaGraph: Offset) {
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
        if (pending == null) return
        pending.hoverPort?.let { commitConnection(pending.from, it) } ?: openNodePick(pending)
    }

    private fun commitConnection(from: PortRef, target: PortRef) {
        val (output, input) = if (from.isOutput) from to target else target to from
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
    private fun isTypeCompatible(workflow: Workflow, output: PortRef, input: PortRef): Boolean =
        isDataAssignable(resolvePort(workflow, output), resolvePort(workflow, input))

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
            state.copy(workflow = state.workflow.withConnection(output, input))
        }
        persist()
    }

    /** Appends the exec or data edge [output] → [input] to this workflow. */
    private fun Workflow.withConnection(output: PortRef, input: PortRef): Workflow = when (output.kind) {
        PortKind.EXECUTION -> copy(
            execConnections = execConnections + ExecConnection(
                id = UUID.randomUUID().toString(),
                fromNodeId = output.nodeId,
                fromPort = output.portName,
                toNodeId = input.nodeId,
                toPort = input.portName,
            ),
        )
        PortKind.DATA -> copy(
            dataConnections = dataConnections + DataConnection(
                id = UUID.randomUUID().toString(),
                fromNodeId = output.nodeId,
                fromPort = output.portName,
                toNodeId = input.nodeId,
                toPort = input.portName,
            ),
        )
    }

    fun cancelPortDrag() {
        _uiState.update { it.copy(pendingConnection = null) }
    }

    // endregion

    // region Drop-to-add (drag a port into empty canvas)

    /**
     * A drag released away from any port opens the node palette filtered to the
     * types that can connect to it. Ignores drags that barely moved so a stray
     * tap on a port handle still does nothing.
     */
    private fun openNodePick(pending: PendingConnection) {
        val origin = portPosition(pending.from)
        val port = resolvePort(_uiState.value.workflow, pending.from)
        if (origin == null || port == null) return
        if ((pending.currentPos - origin).getDistance() <= GraphGeometry.PORT_SNAP_RADIUS) return
        val suggestions = suggestionsFor(
            DragOrigin(kind = pending.from.kind, isOutput = pending.from.isOutput, schema = port.schema),
        )
        _uiState.update {
            it.copy(nodePick = NodePickRequest(pending.from, pending.currentPos, suggestions))
        }
    }

    fun dismissNodePick() {
        _uiState.update { it.copy(nodePick = null) }
    }

    /**
     * Places a node of [typeId] for the pending [NodePickRequest] and wires it
     * to the dragged port in a single edit. The node is positioned so the wired
     * port lands on the drop point; a type with no matching port (picked from
     * the palette's "show all" list) is placed unwired.
     */
    fun addNodeConnectedTo(typeId: NodeTypeId) {
        val pick = _uiState.value.nodePick ?: return
        val definition = NodeTypeRegistry.byId(typeId) ?: return
        val port = pick.suggestions.firstOrNull { it.definition.typeId == typeId }?.port
        val topLeft = if (port != null) {
            pick.dropPosGraph - GraphGeometry.portOffset(definition, port)
        } else {
            pick.dropPosGraph - Offset(GraphGeometry.nodeWidth(definition) / 2f, GraphGeometry.NODE_HEIGHT / 2f)
        }
        val node = WorkflowNode(
            id = NodeId(UUID.randomUUID().toString()),
            typeId = typeId,
            name = definition.displayName,
            x = topLeft.x,
            y = topLeft.y,
            // `@Wired` data inputs are hidden until opted in; reveal the one we
            // are about to wire, otherwise the edge would have no visible handle.
            visibleDataInputs = if (port != null && port.kind == PortKind.DATA && port.direction == Direction.IN) {
                setOf(port.name)
            } else {
                emptySet()
            },
        )
        _uiState.update { state ->
            val withNode = state.workflow.copy(nodes = state.workflow.nodes + node)
            val workflow = if (port == null) {
                withNode
            } else {
                val newRef = PortRef(node.id, port.name, port.direction == Direction.OUT, port.kind)
                val (output, input) = if (pick.from.isOutput) pick.from to newRef else newRef to pick.from
                withNode.withConnection(output, input)
            }
            state.copy(workflow = workflow, selection = Selection.Node(node.id), nodePick = null)
        }
        persist()
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
                    visibleInputPorts(definition, workflow, node)
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
        // Before the initial load lands, [_uiState.workflow] is still the default
        // instance whose id is "default" — saving it would write a junk
        // workflows/default.json instead of this workflow's file.
        val state = _uiState.value
        if (!state.isLoaded) return
        val workflow = state.workflow
        viewModelScope.launch { repository.save(workflow) }
    }

    // region Workflow execution

    private var runJob: Job? = null

    /**
     * Persists the macro's armed state and starts/stops the background engine
     * service accordingly. Distinct from [runWorkflow]: enabling arms the macro
     * in the long-lived service scope so it keeps running after the UI is gone.
     */
    fun setMacroEnabled(enabled: Boolean) {
        val state = _uiState.value
        // Same guard as [persist]: arming before the load completes would target
        // the default id rather than this workflow.
        if (!state.isLoaded) return
        val workflow = state.workflow
        _uiState.update { it.copy(isMacroEnabled = enabled) }
        viewModelScope.launch { repository.setEnabled(workflow.id, enabled) }
        if (enabled) {
            com.example.ottomatic.engine.service.MacroEngineService.start(
                appContext,
                com.example.ottomatic.engine.service.MacroEngineService.ACTION_ENABLE,
                workflow.id,
            )
        } else {
            com.example.ottomatic.engine.service.MacroEngineService.start(
                appContext,
                com.example.ottomatic.engine.service.MacroEngineService.ACTION_DISABLE,
                workflow.id,
            )
        }
    }

    fun runWorkflow() {
        val state = _uiState.value
        // Skip the one-shot preview if it is already running, or if the macro is
        // armed in the background service — double-arming would arm trigger
        // sources (schedule, geofence) a second time.
        if (state.isRunning || state.isMacroEnabled) return
        val workflow = state.workflow
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

    fun updateNodeConfig(nodeId: NodeId, key: ConfigKey, value: String) {
        _uiState.update { state ->
            val nodes = state.workflow.nodes.map { node ->
                if (node.id == nodeId) node.copy(config = node.config + (key to value)) else node
            }
            val workflow = state.workflow.copy(nodes = nodes)
            // When `action.if`'s type chooser changes, drop any data edges wired into its
            // `source`/`value` ports: their schemas are about to change and the old
            // connections would likely fail the new type check. Gated on the node type as
            // well as the key, since "type" is not a reserved config name.
            val isIfType = key == IF_TYPE_CONFIG_KEY &&
                workflow.node(nodeId)?.typeId == IF_TYPE_ID
            val finalWorkflow = if (isIfType) {
                workflow.copy(
                    dataConnections = workflow.dataConnections.filterNot {
                        it.toNodeId == nodeId && (it.toPort == IF_SOURCE_IN || it.toPort == IF_VALUE_IN)
                    },
                )
            } else {
                workflow
            }
            state.copy(workflow = finalWorkflow)
        }
        persist()
    }

    fun setNodeDataInputVisible(nodeId: NodeId, portName: PortName, visible: Boolean) {
        _uiState.update { state ->
            val workflow = state.workflow
            val nodes = workflow.nodes.map { node ->
                if (node.id != nodeId) {
                    node
                } else {
                    val visibleInputs = if (visible) {
                        node.visibleDataInputs + portName
                    } else {
                        node.visibleDataInputs - portName
                    }
                    node.copy(visibleDataInputs = visibleInputs)
                }
            }
            val dataConnections = if (visible) {
                workflow.dataConnections
            } else {
                workflow.dataConnections.filterNot { connection ->
                    connection.toNodeId == nodeId && connection.toPort == portName
                }
            }
            state.copy(workflow = workflow.copy(nodes = nodes, dataConnections = dataConnections))
        }
        persist()
    }

    fun updateNodeName(nodeId: NodeId, name: String) {
        _uiState.update { state ->
            val nodes = state.workflow.nodes.map { node ->
                if (node.id == nodeId) node.copy(name = name) else node
            }
            state.copy(workflow = state.workflow.copy(nodes = nodes))
        }
        persist()
    }

    // endregion

    // region Attached conditions

    /**
     * Attaches a gate to [nodeId] comparing the value node [typeId].
     *
     * Every gate is the same comparison, so what the picker chooses is its *source*.
     * Pre-filling it here is what keeps attaching a condition a single tap, while the
     * rest of the form (operator, literal) narrows itself to the chosen value's type.
     */
    fun addCondition(nodeId: NodeId, typeId: NodeTypeId) {
        val source = AttachedCondition(config = mapOf(IF_SOURCE_KEY to ValueSource.valueSpec(typeId)))
        editNode(nodeId) { node -> node.copy(conditions = node.conditions + source) }
    }

    fun removeCondition(nodeId: NodeId, index: Int) {
        editNode(nodeId) { node ->
            node.copy(conditions = node.conditions.filterIndexed { i, _ -> i != index })
        }
    }

    fun updateConditionConfig(nodeId: NodeId, index: Int, key: ConfigKey, value: String) {
        editCondition(nodeId, index) { it.copy(config = it.config + (key to value)) }
    }

    fun setConditionNegated(nodeId: NodeId, index: Int, negated: Boolean) {
        editCondition(nodeId, index) { it.copy(negated = negated) }
    }

    fun setConditionLogic(nodeId: NodeId, logic: ConditionLogic) {
        editNode(nodeId) { it.copy(conditionLogic = logic) }
    }

    private fun editCondition(nodeId: NodeId, index: Int, edit: (AttachedCondition) -> AttachedCondition) {
        editNode(nodeId) { node ->
            node.copy(conditions = node.conditions.mapIndexed { i, c -> if (i == index) edit(c) else c })
        }
    }

    private fun editNode(nodeId: NodeId, edit: (WorkflowNode) -> WorkflowNode) {
        _uiState.update { state ->
            val nodes = state.workflow.nodes.map { if (it.id == nodeId) edit(it) else it }
            state.copy(workflow = state.workflow.copy(nodes = nodes))
        }
        persist()
    }

    // endregion

    companion object {
        private const val FIT_PADDING = 48f
        private const val MAX_FIT_ZOOM = 1.25f

        fun factory(
            repository: WorkflowRepository,
            triggerHost: TriggerHost,
            executionContext: ExecutionContext,
            appContext: android.content.Context,
            workflowId: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                GraphEditorViewModel(repository, triggerHost, executionContext, appContext, workflowId)
            }
        }
    }
}
