package com.example.ottomatic.feature.grapheditor

import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.service.LogEntry
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.RunLog
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.ottomatic.data.WorkflowRepository
import com.example.ottomatic.domain.model.DataConnection
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.ExecConnection
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.schema.conversionTarget
import com.example.ottomatic.domain.registry.CONVERT_IN
import com.example.ottomatic.domain.registry.CONVERT_TO_KEY
import com.example.ottomatic.domain.registry.CONVERT_TYPE_ID
import com.example.ottomatic.domain.registry.TRANSFORM_OUT
import com.example.ottomatic.domain.registry.IF_SOURCE_IN
import com.example.ottomatic.domain.registry.IF_TYPE_CONFIG_KEY
import com.example.ottomatic.domain.registry.IF_TYPE_ID
import com.example.ottomatic.domain.registry.IF_VALUE_IN
import com.example.ottomatic.domain.registry.DragOrigin
import com.example.ottomatic.domain.registry.NodeSuggestion
import com.example.ottomatic.domain.registry.NodeTypeRegistry
import com.example.ottomatic.domain.registry.COMPARISON_TYPE_IDS
import com.example.ottomatic.domain.registry.JSON_READ_LIST_KEY
import com.example.ottomatic.domain.registry.JSON_READ_TYPE_ID
import com.example.ottomatic.domain.registry.JSON_READ_TYPE_KEY
import com.example.ottomatic.domain.registry.SCRIPT_INPUTS_KEY
import com.example.ottomatic.domain.registry.SCRIPT_OUTPUTS_KEY
import com.example.ottomatic.domain.registry.SCRIPT_TYPE_ID
import com.example.ottomatic.domain.registry.effectiveInputPorts
import com.example.ottomatic.domain.registry.effectiveOutputPorts
import com.example.ottomatic.domain.registry.isDataAssignable
import com.example.ottomatic.domain.registry.suggestionsFor
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.WorkflowRunner
import com.example.ottomatic.engine.service.MacroEngineService
import com.example.ottomatic.engine.trigger.ManualTrigger
import com.example.ottomatic.engine.trigger.TriggerHost
import com.example.ottomatic.engine.validation.GraphValidation
import com.example.ottomatic.engine.validation.GraphValidator
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Pan/zoom state of the canvas. Offset is in screen px, scale is unitless. */
data class CanvasTransform(
    val offset: Offset = Offset.Zero,
    val scale: Float = 1f,
) {
    /**
     * Screen px to graph units. Graph units are dp at zoom 1, so undoing the
     * transform means dividing by [scale] *and* by the display density.
     */
    fun toGraph(positionPx: Offset, density: Float): Offset = (positionPx - offset) / (scale * density)
}

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

data class GraphEditorUiState(
    val workflow: Workflow = Workflow(),
    val transform: CanvasTransform = CanvasTransform(),
    val selection: Selection = Selection.EMPTY,
    val interaction: GraphInteraction = GraphInteraction(),
    val pendingConnection: PendingConnection? = null,
    val nodePick: NodePickRequest? = null,
    val revealedLabel: PortRef? = null,
    val isLoaded: Boolean = false,
    val isRunning: Boolean = false,
    val isMacroEnabled: Boolean = false,
)

// TooManyFunctions: the editor surface — transform, selection, node and connection editing.
// LongParameterList: one per collaborator; the alternative is a bag object that hides them.
@Suppress("TooManyFunctions", "LongParameterList")
class GraphEditorViewModel(
    private val repository: WorkflowRepository,
    private val triggerHost: TriggerHost,
    private val executionContext: ExecutionContext,
    private val runLog: RunLog,
    private val appContext: android.content.Context,
    private val appScope: CoroutineScope,
    private val workflowId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GraphEditorUiState())
    val uiState: StateFlow<GraphEditorUiState> = _uiState.asStateFlow()

    /**
     * This workflow's console, and the state around it.
     *
     * Deliberately **not** part of [GraphEditorUiState]. That state drives the
     * canvas, and a run that logs a line per node would repaint the whole graph
     * for each one — [GraphEditorViewModel] is not a stable type to Compose, so
     * `GraphCanvas` would not skip. Kept as separate flows, only the console
     * itself collects them.
     */
    val console: StateFlow<List<LogEntry>> = runLog.entries(workflowId)

    private val _consoleMinLevel = MutableStateFlow(LogLevel.INFO)
    val consoleMinLevel: StateFlow<LogLevel> = _consoleMinLevel.asStateFlow()

    /** Drives the badge on the console button: what is worth looking at. */
    val consoleProblems: StateFlow<Int> = console
        .map { entries -> entries.count { it.level >= LogLevel.WARN } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_STOP_TIMEOUT_MS), 0)

    fun setConsoleMinLevel(level: LogLevel) {
        _consoleMinLevel.value = level
    }

    fun clearConsole() = runLog.clear(workflowId)

    /**
     * Selects the node a console line came from, if it is still on the canvas.
     *
     * A log line outlives the node it names — a run persists, an edit does not
     * ask the log's permission — so a stale id selects nothing rather than
     * leaving the canvas pointing at something that is gone.
     */
    fun selectNode(nodeId: NodeId) {
        if (_uiState.value.workflow.node(nodeId) == null) return
        _uiState.update { it.selectingOnly(nodeId) }
    }

    /**
     * Selects one edge outright.
     *
     * [tapConnection] *toggles*, which is right for a tap on the canvas and wrong
     * for a tap in a list: picking a problem must land on its wire, not clear the
     * selection because the wire happened to be selected already.
     */
    fun selectConnection(connectionId: String) {
        _uiState.update { it.copy(selection = Selection.ofConnection(connectionId)) }
    }

    /**
     * What is wrong with the graph as it stands, recomputed as it is edited.
     *
     * A sibling flow rather than a field on [GraphEditorUiState], for two reasons
     * that have nothing to do with how often it changes:
     *
     *  - **it cannot go stale.** There are twenty-odd `_uiState.update { }` call
     *    sites; a stored field would have to be recomputed correctly by every one
     *    of them, and by the next one somebody adds. A derivation cannot be
     *    forgotten.
     *  - **it costs nothing to drag.** `dragSelectedNodes` rewrites the workflow
     *    on every pointer event. [Workflow.runtimeSignature] omits `x`/`y`, so the
     *    de-dupe collapses a whole drag to zero validator runs, where a stored
     *    field would revalidate per frame.
     *
     * The key is the runtime signature *plus* node names: names are what the
     * messages are written in, and renaming a node is not a per-frame operation.
     */
    val validation: StateFlow<GraphValidation> = uiState
        .map { it.workflow }
        .distinctUntilChangedBy { workflow -> workflow.runtimeSignature() to workflow.nodes.map { it.name } }
        .map { GraphValidator(it).validate() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_STOP_TIMEOUT_MS), GraphValidation.EMPTY)

    /**
     * The [Workflow.runtimeSignature] the background service is currently
     * running, as far as this editor knows. Seeded from the loaded workflow so
     * opening an armed macro and changing nothing re-arms nothing.
     */
    private var lastArmedSignature: Workflow.RuntimeSignature? = null

    init {
        viewModelScope.launch {
            val workflow = repository.load(workflowId) ?: Workflow(id = workflowId)
            lastArmedSignature = workflow.runtimeSignature()
            _uiState.update {
                it.copy(workflow = workflow, isLoaded = true, isMacroEnabled = workflow.enabled)
            }
        }
    }

    // region Canvas transform

    /**
     * A second finger landed: the canvas is taking the gesture over.
     *
     * Everything a single finger might have had in flight is abandoned, and
     * abandoned *without* a trace — a node goes back where it started, a pending
     * wire disappears. The rule the gesture layer is built around is that an
     * accidental pinch changes nothing.
     *
     * Every step is idempotent because this runs on the Initial pointer pass and
     * each child then reports its own cancellation a pass later.
     */
    fun beginCanvasTransform() {
        cancelNodeGesture()
        cancelMarquee()
        cancelPortDrag()
    }

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

    fun tapNode(nodeId: NodeId) {
        _uiState.update { it.withTappedNode(nodeId) }
    }

    fun tapConnection(connectionId: String) {
        _uiState.update { it.withTappedConnection(connectionId) }
    }

    fun longPressNode(nodeId: NodeId) {
        _uiState.update { it.withLongPressedNode(nodeId) }
    }

    fun startMarquee(graphPos: Offset) {
        _uiState.update { it.withMarqueeStarted(graphPos) }
    }

    fun moveMarquee(graphPos: Offset) {
        _uiState.update { it.withMarqueeMoved(graphPos) }
    }

    fun commitMarquee() {
        _uiState.update { it.withMarqueeCommitted() }
    }

    fun cancelMarquee() {
        _uiState.update { it.withMarqueeCancelled() }
    }

    fun clearSelection() {
        _uiState.update { it.withClearedSelection() }
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
        if (_uiState.value.selection.isEmpty) return
        _uiState.update { state ->
            state.copy(
                workflow = state.workflow.withoutSelection(state.selection),
                selection = Selection.EMPTY,
                interaction = state.interaction.copy(isMultiSelect = false),
            )
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
            state.copy(workflow = state.workflow.copy(nodes = state.workflow.nodes + node))
                .selectingOnly(node.id)
        }
        persist()
    }

    /** A finger landed on a node card. Records the undo point; changes nothing. */
    fun beginNodeGesture() {
        _uiState.update { it.withNodeGestureBegun() }
    }

    /** The finger crossed the slop: grab [nodeId] and fix the set that will move. */
    fun beginNodeDrag(nodeId: NodeId) {
        _uiState.update { it.withNodeDragBegun(nodeId) }
    }

    fun dragSelectedNodes(deltaGraph: Offset) {
        _uiState.update { it.withDragDelta(deltaGraph) }
    }

    /**
     * The finger lifted. Saves only if something actually moved — a press that
     * merely selected must not schedule a write of an unchanged graph.
     */
    fun endNodeGesture() {
        val moved = _uiState.value.hasUnsavedNodeMove
        _uiState.update { it.withNodeGestureEnded() }
        if (moved) persist()
    }

    /**
     * The gesture was cancelled or taken over by the canvas: put positions,
     * selection and mode back. Deliberately does **not** persist — nothing
     * net-changed, and a redundant save would rewrite the file for a pinch.
     */
    fun cancelNodeGesture() {
        _uiState.update { it.withNodeGestureReverted() }
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
        if (output.kind == PortKind.DATA && !isTypeCompatible(workflow, output, input)) {
            insertConversion(workflow, output, input)
            return
        }
        if (!connectionExists(workflow, output, input)) addConnection(output, input)
    }

    /**
     * Bridges a DATA drop the type system refused, by placing a `transform.convert`
     * node into the wire pre-set to the conversion that fits — Unreal Blueprints'
     * autocast, and the reason a mismatched drop is not simply thrown away.
     *
     * The conversion is a real node rather than a coercion on the edge, so it is
     * visible, deletable, and carries its own "If it fails" setting. A drop with no
     * conversion at all (text into a struct) is still silently refused.
     */
    private fun insertConversion(workflow: Workflow, output: PortRef, input: PortRef) {
        val sourceSchema = resolvePort(workflow, output)?.schema
        val targetSchema = resolvePort(workflow, input)?.schema
        val to = conversionTarget(sourceSchema, targetSchema) ?: return
        val definition = NodeTypeRegistry.byId(CONVERT_TYPE_ID) ?: return
        val midpoint = midpoint(output, input)
        val convert = WorkflowNode(
            id = NodeId(UUID.randomUUID().toString()),
            typeId = CONVERT_TYPE_ID,
            name = definition.displayName,
            x = midpoint.x - GraphGeometry.nodeWidth(definition) / 2f,
            y = midpoint.y - GraphGeometry.NODE_HEIGHT / 2f,
            config = mapOf(CONVERT_TO_KEY to to.name),
            // The value input is a wildcard rather than a `@Wired` property, but
            // every DATA input starts hidden — reveal it or the edge lands nowhere.
            visibleDataInputs = setOf(CONVERT_IN),
        )
        val intoConvert = PortRef(convert.id, CONVERT_IN, isOutput = false, kind = PortKind.DATA)
        val outOfConvert = PortRef(convert.id, TRANSFORM_OUT, isOutput = true, kind = PortKind.DATA)
        _uiState.update { state ->
            val placed = state.workflow.copy(nodes = state.workflow.nodes + convert)
            state.copy(
                workflow = placed
                    .withConnection(output, intoConvert)
                    .withConnection(outOfConvert, input),
            ).selectingOnly(convert.id)
        }
        persist()
    }

    /**
     * Where to drop an inserted node: halfway along the wire it is joining, nudged
     * clear of the two endpoints when their positions are unknown.
     */
    private fun midpoint(output: PortRef, input: PortRef): Offset {
        val from = portPosition(output)
        val to = portPosition(input)
        return when {
            from != null && to != null -> (from + to) / 2f
            else -> from ?: to ?: Offset.Zero
        }
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
            state.copy(workflow = workflow, nodePick = null).selectingOnly(node.id)
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

    private var saveJob: Job? = null

    /**
     * Set once [deleteWorkflow] has removed this workflow's file, and never
     * cleared: it makes every remaining write a no-op.
     *
     * Without it, deleting from the editor does nothing visible. The debounced
     * [persist] may have a write in flight, and [onCleared] saves unconditionally
     * on the way out — and the way out is exactly what deleting triggers. Either
     * one recreates the file we just removed.
     */
    private var isDeleted = false

    /**
     * Schedules a debounced write of the current graph. Every mutator calls this;
     * typing a config value fires it per keystroke, so coalescing matters — each
     * write is a full pretty-printed JSON encode.
     */
    private fun persist() {
        // Before the initial load lands, [_uiState.workflow] is still the default
        // instance whose id is "default" — saving it would write a junk
        // workflows/default.json instead of this workflow's file.
        if (!_uiState.value.isLoaded || isDeleted) return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            flush()
        }
    }

    /**
     * Writes the graph and, when the change actually affects execution, tells the
     * engine service to re-read it. This is what makes an edit to an armed macro
     * take effect without the user toggling it off and on.
     *
     * Gated on [Workflow.runtimeSignature] so cosmetic edits — dragging a node,
     * renaming it — never re-arm: re-arming re-registers geofences and re-enqueues
     * periodic work, which is far too expensive to do on every drag frame.
     */
    private suspend fun flush() {
        val state = _uiState.value
        if (!state.isLoaded || isDeleted) return
        val workflow = state.workflow
        repository.save(workflow)
        val signature = workflow.runtimeSignature()
        if (state.isMacroEnabled && signature != lastArmedSignature) {
            lastArmedSignature = signature
            MacroEngineService.start(appContext, MacroEngineService.ACTION_RELOAD, workflow.id)
        }
    }

    override fun onCleared() {
        // viewModelScope is already cancelled here, so a pending debounced save
        // would be dropped on the way out of the editor. Re-issue it on the
        // process-lifetime scope instead.
        saveJob?.cancel()
        val state = _uiState.value
        if (state.isLoaded && !isDeleted) {
            val workflow = state.workflow
            val enabled = state.isMacroEnabled
            val armed = lastArmedSignature
            appScope.launch {
                repository.save(workflow)
                if (enabled && workflow.runtimeSignature() != armed) {
                    // The Activity may already be gone, and Android 12+ forbids
                    // starting a foreground service from the background. The save
                    // above is the part that must not be lost; the reload is best
                    // effort and REARM_ALL on next launch covers the miss.
                    runCatching {
                        MacroEngineService.start(
                            appContext,
                            MacroEngineService.ACTION_RELOAD,
                            workflow.id,
                        )
                    }
                }
            }
        }
        super.onCleared()
    }

    // region Workflow-level actions

    /**
     * Renames the workflow.
     *
     * Deliberately not `repository.rename`, the way the workflow list does it:
     * the editor holds the whole graph in memory and [flush] writes all of it,
     * so a repository-side rename would be overwritten by the next debounced
     * save, and again by [onCleared]'s final one. Going through [persist] is the
     * same route `updateNodeName` takes.
     *
     * [Workflow.runtimeSignature] omits the name, so renaming an armed macro
     * never re-arms it.
     */
    fun renameWorkflow(name: String) {
        _uiState.update { it.copy(workflow = it.workflow.copy(name = name)) }
        persist()
    }

    /**
     * Deletes this workflow and calls [onDeleted] once its file is gone, so the
     * caller can leave the editor.
     *
     * Mirrors `WorkflowListViewModel.delete` — disarm first so the engine
     * releases this workflow's trigger sources before the file it was armed from
     * disappears, and drop its console, which would otherwise outlive it and
     * surface a deleted macro's errors under a recreated one.
     *
     * The extra step the list does not need is [isDeleted]: leaving the editor
     * clears this ViewModel, and clearing it saves. See the flag's own note.
     */
    fun deleteWorkflow(onDeleted: () -> Unit) {
        if (!_uiState.value.isLoaded || isDeleted) return
        val id = _uiState.value.workflow.id
        isDeleted = true
        saveJob?.cancel()
        MacroEngineService.start(appContext, MacroEngineService.ACTION_DISABLE, id)
        runLog.clear(id)
        // viewModelScope survives this: the ViewModel is cleared by the
        // navigation that [onDeleted] performs, which is the last thing here.
        viewModelScope.launch {
            repository.delete(id)
            onDeleted()
        }
    }

    // endregion

    // region Workflow execution

    private var runJob: Job? = null

    /**
     * Persists the macro's armed state and starts/stops the background engine
     * service accordingly. Distinct from [runWorkflow]: enabling arms the macro
     * in the long-lived service scope so it keeps running after the UI is gone.
     */
    fun setMacroEnabled(enabled: Boolean) {
        // Same guard as [persist]: arming before the load completes would target
        // the default id rather than this workflow.
        if (!_uiState.value.isLoaded) return
        // Drop any pending debounced save: it would race this write, and the
        // save below already carries the same graph plus the new flag.
        saveJob?.cancel()
        // [enabled] lives on the workflow, not just on the UI mirror flag —
        // otherwise the next persist() writes the load-time value back and
        // silently clobbers the toggle on disk.
        _uiState.update {
            it.copy(workflow = it.workflow.copy(enabled = enabled), isMacroEnabled = enabled)
        }
        val workflow = _uiState.value.workflow
        // Arming reloads from disk, so whatever we write below is what runs.
        lastArmedSignature = workflow.runtimeSignature()
        val action = if (enabled) {
            MacroEngineService.ACTION_ENABLE
        } else {
            MacroEngineService.ACTION_DISABLE
        }
        viewModelScope.launch {
            // A full save rather than repository.setEnabled: that would re-read
            // the file and write the *stale* graph back, discarding unsaved
            // edits. It must complete before the service starts, because arm()
            // loads the workflow from disk.
            repository.save(workflow)
            MacroEngineService.start(appContext, action, workflow.id)
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
        // Stopping the preview promises the same silence disabling a macro does;
        // a fire-and-forget sound would otherwise play on with nothing running.
        executionContext.systemServices.stopSounds()
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
            state.copy(workflow = pruneRetypedEdges(workflow, nodeId, key))
        }
        persist()
    }

    /**
     * Drops the data edges a config change has just invalidated.
     *
     * Two config keys retype a placed node's ports through [effectivePorts], and
     * an edge left behind on a port that no longer exists — or no longer has the
     * type it was checked against — is worse than no edge: it draws, it saves,
     * and it silently carries nothing.
     *
     * Both are gated on the node's own typeId as well as the key, because
     * neither "type" nor "outputs" is a reserved config name.
     */
    private fun pruneRetypedEdges(workflow: Workflow, nodeId: NodeId, key: ConfigKey): Workflow {
        val typeId = workflow.node(nodeId)?.typeId
        return when {
            // A comparison's type chooser (`action.if`, `action.while`): the
            // `source`/`value` schemas are about to change and the old connections
            // would likely fail the new check.
            key == IF_TYPE_CONFIG_KEY && typeId in COMPARISON_TYPE_IDS -> workflow.copy(
                dataConnections = workflow.dataConnections.filterNot {
                    it.toNodeId == nodeId && (it.toPort == IF_SOURCE_IN || it.toPort == IF_VALUE_IN)
                },
            )
            // A script's port lists: an edited row can rename a port, delete it
            // or retype it, so every edge touching this node is re-checked
            // against the ports it now has.
            key in SCRIPT_PORT_KEYS && typeId == SCRIPT_TYPE_ID ->
                workflow.copy(dataConnections = workflow.dataConnections.filter { it.stillValid(workflow, nodeId) })
            // A JSON read's result type and its list switch both retype the one
            // output port, so an edge that fitted a Text no longer fits a list of
            // them. Re-checked rather than dropped, for the same reason as above.
            key in JSON_READ_PORT_KEYS && typeId == JSON_READ_TYPE_ID ->
                workflow.copy(dataConnections = workflow.dataConnections.filter { it.stillValid(workflow, nodeId) })
            else -> workflow
        }
    }

    /**
     * True when this edge still connects two ports that exist and type-check.
     *
     * Re-checking beats dropping every edge on the node the way `action.if`'s
     * type chooser does: a port list is edited one character at a time, so
     * clearing the lot on each keystroke would delete work the user can see is
     * still correct. An edge only goes when its port is genuinely gone or its
     * type no longer fits.
     */
    private fun DataConnection.stillValid(workflow: Workflow, nodeId: NodeId): Boolean {
        if (toNodeId != nodeId && fromNodeId != nodeId) return true
        val from = resolvePort(workflow, PortRef(fromNodeId, fromPort, isOutput = true, kind = PortKind.DATA))
        val to = resolvePort(workflow, PortRef(toNodeId, toPort, isOutput = false, kind = PortKind.DATA))
        return from != null && to != null && isDataAssignable(from, to)
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

    companion object {
        private const val FIT_PADDING = 48f
        private const val MAX_FIT_ZOOM = 1.25f

        /**
         * How long the editor waits for edits to settle before writing. Long
         * enough to coalesce a burst of keystrokes, short enough that an edit
         * feels like it takes effect immediately.
         */
        private const val SAVE_DEBOUNCE_MS = 500L

        /** Keeps derived console state alive across a configuration change. */
        private const val FLOW_STOP_TIMEOUT_MS = 5_000L

        /** The two `@Ports` config keys on `action.script`, both of which retype its ports. */
        private val SCRIPT_PORT_KEYS = setOf(SCRIPT_INPUTS_KEY, SCRIPT_OUTPUTS_KEY)

        private val JSON_READ_PORT_KEYS = setOf(JSON_READ_TYPE_KEY, JSON_READ_LIST_KEY)

        @Suppress("LongParameterList") // Mirrors the ViewModel's injected dependencies 1:1.
        fun factory(
            repository: WorkflowRepository,
            triggerHost: TriggerHost,
            executionContext: ExecutionContext,
            runLog: RunLog,
            appContext: android.content.Context,
            appScope: CoroutineScope,
            workflowId: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                GraphEditorViewModel(
                    repository,
                    triggerHost,
                    executionContext,
                    runLog,
                    appContext,
                    appScope,
                    workflowId,
                )
            }
        }
    }
}

/**
 * Selects a freshly placed node and nothing else.
 *
 * Placing a node — from the palette, from a dragged-off pin, or as an autocast
 * Convert — is always a deliberate single-node act, so it also leaves multi-select
 * mode. Staying in it would mean the next tap toggled the new node straight back
 * out of the selection.
 */
private fun GraphEditorUiState.selectingOnly(nodeId: NodeId): GraphEditorUiState = copy(
    selection = Selection.ofNode(nodeId),
    interaction = interaction.copy(isMultiSelect = false),
)
