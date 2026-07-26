package com.example.ottomatic.feature.grapheditor

import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.ConfigKey
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.registry.NodeTypeRegistry
import com.example.ottomatic.domain.registry.effectiveConfigSchema
import com.example.ottomatic.domain.registry.effectiveInputPorts
import com.example.ottomatic.engine.trigger.GeofenceTrigger
import com.example.ottomatic.feature.geofence.GeofencePlacesViewModel
import com.example.ottomatic.feature.geofence.LocalGeofencePlaces
import kotlin.math.roundToInt

@Composable
fun GraphEditorScreen(
    viewModel: GraphEditorViewModel,
    geofencePlaces: GeofencePlacesViewModel,
    showBatteryPrompt: Boolean = false,
    onDismissBatteryPrompt: () -> Unit = {},
    onConfirmBatteryPrompt: () -> Unit = {},
) {
    // Published rather than passed down: the `@Picker` config field and the node
    // cards both need the place library, and neither is reachable from here
    // without threading a geofence-shaped parameter through generic code.
    CompositionLocalProvider(LocalGeofencePlaces provides geofencePlaces) {
        GraphEditorContent(
            viewModel = viewModel,
            showBatteryPrompt = showBatteryPrompt,
            onDismissBatteryPrompt = onDismissBatteryPrompt,
            onConfirmBatteryPrompt = onConfirmBatteryPrompt,
        )
    }
}

@Composable
private fun GraphEditorContent(
    viewModel: GraphEditorViewModel,
    showBatteryPrompt: Boolean,
    onDismissBatteryPrompt: () -> Unit,
    onConfirmBatteryPrompt: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val density = LocalDensity.current.density
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var showPalette by remember { mutableStateOf(false) }
    var showConfig by remember { mutableStateOf(false) }
    var pickConditionFor by remember { mutableStateOf<NodeId?>(null) }
    var hasAutoFitted by remember { mutableStateOf(false) }

    // Center the workflow in the viewport once it is loaded and the canvas is measured.
    LaunchedEffect(state.isLoaded, canvasSize) {
        if (!hasAutoFitted && state.isLoaded && canvasSize != IntSize.Zero) {
            hasAutoFitted = true
            viewModel.fitToContent(Size(canvasSize.width.toFloat(), canvasSize.height.toFloat()), density)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EditorColors.canvasBackground),
    ) {
        EditorTopBar(
            title = state.workflow.name,
            nodeCount = state.workflow.nodes.size,
            hasSelection = state.selection != null,
            isMacroEnabled = state.isMacroEnabled,
            onToggleEnabled = { viewModel.setMacroEnabled(it) },
            onConfigure = { showConfig = true },
            onDelete = { viewModel.deleteSelection() },
        )
        Box(modifier = Modifier.fillMaxSize()) {
            GraphCanvas(
                state = state,
                viewModel = viewModel,
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { canvasSize = it },
            )
            if (state.isLoaded && state.workflow.nodes.isEmpty()) {
                EmptyHint(modifier = Modifier.align(Alignment.Center))
            }
            ZoomControls(
                scale = state.transform.scale,
                onZoomIn = { viewModel.zoomBy(1.2f, canvasSize.centerPx()) },
                onZoomOut = { viewModel.zoomBy(1f / 1.2f, canvasSize.centerPx()) },
                onResetZoom = { viewModel.zoomBy(1f / state.transform.scale, canvasSize.centerPx()) },
                onFit = {
                    viewModel.fitToContent(Size(canvasSize.width.toFloat(), canvasSize.height.toFloat()), density)
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 14.dp, bottom = 18.dp),
            )
            FloatingActionButton(
                onClick = { showPalette = true },
                containerColor = EditorColors.actionAccent,
                contentColor = EditorColors.textPrimary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 18.dp, bottom = 18.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add node")
            }
            if (state.isRunning) {
                FloatingActionButton(
                    onClick = { viewModel.stopWorkflow() },
                    containerColor = EditorColors.triggerAccent,
                    contentColor = EditorColors.textPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 88.dp, bottom = 18.dp),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop workflow")
                }
            } else {
                FloatingActionButton(
                    onClick = { viewModel.runWorkflow() },
                    containerColor = EditorColors.triggerAccent,
                    contentColor = EditorColors.textPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 88.dp, bottom = 18.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Run workflow")
                }
            }
        }
    }

    if (showPalette) {
        NodePaletteOverlay(
            onDismiss = { showPalette = false },
            onPick = { definition ->
                showPalette = false
                val transform = state.transform
                val centerPx = canvasSize.centerPx()
                val centerGraph = (centerPx - transform.offset) / (transform.scale * density)
                viewModel.addNode(
                    typeId = definition.typeId,
                    positionGraph = centerGraph - Offset(
                        GraphGeometry.nodeWidth(definition) / 2f,
                        GraphGeometry.NODE_HEIGHT / 2f,
                    ),
                )
            },
        )
    }

    // A connection drag released on empty canvas: same palette, filtered to the
    // node types that can wire straight to the port it came from.
    state.nodePick?.let { pick ->
        NodePaletteOverlay(
            onDismiss = { viewModel.dismissNodePick() },
            onPick = { definition -> viewModel.addNodeConnectedTo(definition.typeId) },
            title = "Connect from ${originLabel(state.workflow, pick.from)}",
            restrictedTo = pick.suggestions.map { it.definition.typeId }.toSet(),
        )
    }

    if (showConfig) {
        val selection = state.selection
        val node = (selection as? Selection.Node)?.let { ref ->
            state.workflow.node(ref.nodeId)
        }
        if (node != null) {
            NodeConfigOverlay(
                workflow = state.workflow,
                node = node,
                onDismiss = { showConfig = false },
                onNameChange = { name -> viewModel.updateNodeName(node.id, name) },
                onConfigChange = { key, value -> viewModel.updateNodeConfig(node.id, key, value) },
                onDataInputVisibilityChange = { portName, visible ->
                    viewModel.setNodeDataInputVisible(node.id, portName, visible)
                },
                conditionActions = ConditionActions(
                    onAdd = { pickConditionFor = node.id },
                    onRemove = { index -> viewModel.removeCondition(node.id, index) },
                    onConfigChange = { index, key, value ->
                        viewModel.updateConditionConfig(node.id, index, key, value)
                    },
                    onNegatedChange = { index, negated ->
                        viewModel.setConditionNegated(node.id, index, negated)
                    },
                    onLogicChange = { logic -> viewModel.setConditionLogic(node.id, logic) },
                ),
            )
        } else {
            showConfig = false
        }
    }

    // Attaching a condition reuses the node palette, restricted to the condition
    // kind — the same list you would drop on the canvas, picked to live inside a
    // node instead.
    pickConditionFor?.let { nodeId ->
        NodePaletteOverlay(
            onDismiss = { pickConditionFor = null },
            onPick = { definition ->
                pickConditionFor = null
                viewModel.addCondition(nodeId, definition.typeId)
            },
            title = "Add condition",
            restrictedTo = NodeTypeRegistry.byKind(NodeKind.CONDITION).map { it.typeId }.toSet(),
        )
    }

    if (showBatteryPrompt) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismissBatteryPrompt,
            title = { Text("Disable battery optimisation") },
            text = {
                Text(
                    "Ottomatic couldn't resume your macros in the background after the last reboot. " +
                        "To keep automation running without intervention, allow Ottomatic to run " +
                        "without battery restrictions.",
                    color = EditorColors.textPrimary,
                    fontSize = 14.sp,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = onConfirmBatteryPrompt) {
                    Text("Allow")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = onDismissBatteryPrompt) {
                    Text("Not now")
                }
            },
        )
    }
}

private fun IntSize.centerPx(): Offset = Offset(width / 2f, height / 2f)

@Composable
private fun EditorTopBar(
    title: String,
    nodeCount: Int,
    hasSelection: Boolean,
    isMacroEnabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onConfigure: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(color = EditorColors.chrome) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(60.dp)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = title,
                    color = EditorColors.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (nodeCount == 1) "1 node" else "$nodeCount nodes",
                    color = EditorColors.textSecondary,
                    fontSize = 11.sp,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(
                checked = isMacroEnabled,
                onCheckedChange = onToggleEnabled,
            )
            Text(
                text = "Enabled",
                color = EditorColors.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 6.dp, end = 8.dp),
            )
            if (hasSelection) {
                IconButton(onClick = onConfigure) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Configure node",
                        tint = EditorColors.textPrimary,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Delete selection",
                        tint = EditorColors.nodeSelectedBorder,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Your canvas is empty",
            color = EditorColors.textPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Tap + to add your first node",
            color = EditorColors.textSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ZoomControls(
    scale: Float,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onResetZoom: () -> Unit,
    onFit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = EditorColors.chrome,
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onZoomIn, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "Zoom in", tint = EditorColors.textPrimary)
            }
            Text(
                text = "${(scale * 100).roundToInt()}%",
                color = EditorColors.textSecondary,
                fontSize = 11.sp,
                modifier = Modifier
                    .clickable(onClick = onResetZoom)
                    .padding(vertical = 4.dp, horizontal = 6.dp),
            )
            IconButton(onClick = onZoomOut, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Remove, contentDescription = "Zoom out", tint = EditorColors.textPrimary)
            }
            HorizontalDivider(
                modifier = Modifier.width(28.dp),
                color = EditorColors.chromeBorder,
            )
            IconButton(onClick = onFit, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.FitScreen, contentDescription = "Fit to screen", tint = EditorColors.textPrimary)
            }
        }
    }
}

/** "Node name › Port label" for the port a connection drag started from. */
private fun originLabel(workflow: com.example.ottomatic.domain.model.Workflow, ref: PortRef): String {
    val nodeName = workflow.node(ref.nodeId)?.name ?: return ref.portName.value
    val portLabel = resolvePort(workflow, ref)?.label ?: ref.portName.value
    return "$nodeName › $portLabel"
}

/** The config keys a (possibly absent) schema renders a field for. */
private fun schemaKeys(
    schema: com.example.ottomatic.domain.registry.NodeConfigSchema?,
): Set<ConfigKey> = schema?.fields?.map { it.key }?.toSet().orEmpty()

/**
 * "Node › Port" for whatever feeds [port] on [nodeId], or `null` when nothing is
 * connected there yet. Data inputs accept a single edge, so the first is the one.
 */
private fun dataSourceLabel(
    workflow: com.example.ottomatic.domain.model.Workflow,
    nodeId: NodeId,
    port: PortName,
): String? = workflow.incomingData(nodeId, port).firstOrNull()?.let { connection ->
    originLabel(
        workflow,
        PortRef(
            nodeId = connection.fromNodeId,
            portName = connection.fromPort,
            isOutput = true,
            kind = PortKind.DATA,
        ),
    )
}

@Composable
private fun NodeConfigOverlay(
    workflow: com.example.ottomatic.domain.model.Workflow,
    node: WorkflowNode,
    onDismiss: () -> Unit,
    onNameChange: (String) -> Unit,
    onConfigChange: (ConfigKey, String) -> Unit,
    onDataInputVisibilityChange: (PortName, Boolean) -> Unit,
    conditionActions: ConditionActions,
) {
    val definition = NodeTypeRegistry.byId(node.typeId)
    val schema = definition?.let { effectiveConfigSchema(it, workflow, node) }
    val dataInputPorts = definition?.let { effectiveInputPorts(it, workflow, node) }
        ?.filter { it.kind == PortKind.DATA }
        .orEmpty()
    // A config key and its DATA port name are the same string by construction
    // (see WorkflowNode.config), which is what lets each field carry its own
    // wiring toggle instead of a detached list at the bottom of the sheet.
    val portByKey = dataInputPorts.associateBy { ConfigKey(it.name.value) }
    // What is left over are the wildcardDataIn ports (e.g. action.break's
    // `struct`): real inputs with no config field to sit beside, so they keep a
    // section of their own.
    val fieldlessPorts = dataInputPorts.filterNot { ConfigKey(it.name.value) in schemaKeys(schema) }
    // One wirable field puts every field in this sheet on the narrower measure, so
    // they keep a common right edge rather than the wirable ones looking clipped.
    val gutter = schemaKeys(schema).any { it in portByKey }
    // The form grows without bound — schema fields, attached conditions, data
    // inputs — so it gets a full screen to scroll in.
    EditorOverlay(title = "Configure", onClose = onDismiss) { _ ->
        Column(
            // imePadding/navigationBarsPadding sit outside the scroll so the
            // keyboard shrinks the viewport rather than the scrolling content.
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        ) {
            Text(
                text = definition?.displayName ?: node.typeId.value,
                color = EditorColors.textSecondary,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.height(12.dp))
            NodePermissionNotice(definition = definition)
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = node.name,
                onValueChange = onNameChange,
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = if (gutter) ConfigFieldToggleGutter else 0.dp),
            )
            if (schema != null) {
                Spacer(modifier = Modifier.height(12.dp))
                schema.fields.forEach { field ->
                    val port = portByKey[field.key]
                    ConfigFieldRow(
                        field = field,
                        port = port,
                        wired = port != null && port.name in node.visibleDataInputs,
                        sourceLabel = port?.let { dataSourceLabel(workflow, node.id, it.name) },
                        value = node.config[field.key] ?: field.defaultValue,
                        reserveToggleGutter = gutter,
                        onValueChange = { onConfigChange(field.key, it) },
                        onWiredChange = { port?.let { p -> onDataInputVisibilityChange(p.name, it) } },
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
                ConfigFormHint(node)
            }
            Spacer(modifier = Modifier.height(6.dp))
            ConditionsSection(
                workflow = workflow,
                node = node,
                actions = conditionActions,
            )
            if (fieldlessPorts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Data inputs",
                    color = EditorColors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                fieldlessPorts.forEach { port ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = port.label,
                            color = EditorColors.textSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                        androidx.compose.material3.Switch(
                            checked = port.name in node.visibleDataInputs,
                            onCheckedChange = { onDataInputVisibilityChange(port.name, it) },
                        )
                    }
                }
            }
        }
    }
}
