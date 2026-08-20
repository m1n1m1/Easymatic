package com.example.ottomatic.feature.grapheditor

import androidx.compose.ui.platform.LocalContext
import com.example.ottomatic.R
import androidx.compose.ui.res.stringResource
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.ConfigKey
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Remove
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
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.registry.NodeTypeRegistry
import com.example.ottomatic.domain.registry.effectiveConfigSchema
import com.example.ottomatic.domain.registry.effectiveInputPorts
import com.example.ottomatic.engine.trigger.GeofenceTrigger
import com.example.ottomatic.engine.validation.GraphValidation
import com.example.ottomatic.feature.geofence.GeofencePlacesViewModel
import com.example.ottomatic.feature.geofence.LocalGeofencePlaces
import com.example.ottomatic.feature.ai.AiConnectionsViewModel
import com.example.ottomatic.feature.ai.LocalAiConnections
import com.example.ottomatic.feature.mail.LocalMailAccounts
import com.example.ottomatic.feature.mail.MailAccountsViewModel
import com.example.ottomatic.feature.nfc.LocalNfcTags
import com.example.ottomatic.feature.nfc.NfcTagsViewModel
import com.example.ottomatic.feature.smarthome.LocalSmartHome
import com.example.ottomatic.feature.smarthome.SmartHomeViewModel
import com.example.ottomatic.feature.variables.GlobalVariablesViewModel
import com.example.ottomatic.feature.variables.LocalVariables
import com.example.ottomatic.feature.workflowlist.LocalMacros
import com.example.ottomatic.feature.i18n.NodeText
import com.example.ottomatic.feature.i18n.rememberNodeText
import com.example.ottomatic.feature.workflowlist.MacroLibrary
import kotlin.math.roundToInt

@Composable
fun GraphEditorScreen(
    viewModel: GraphEditorViewModel,
    geofencePlaces: GeofencePlacesViewModel,
    nfcTags: NfcTagsViewModel,
    mailAccounts: MailAccountsViewModel,
    smartHome: SmartHomeViewModel,
    aiConnections: AiConnectionsViewModel,
    globalVariables: GlobalVariablesViewModel,
    onBack: () -> Unit,
) {
    // Published rather than passed down: the `@Picker` config fields and the node
    // cards need these libraries, and none of them is reachable from here without
    // threading a geofence- or variable-shaped parameter through generic code.
    val variables = remember(viewModel, globalVariables) {
        EditorVariableLibrary(viewModel, globalVariables)
    }
    val macros = remember(viewModel) { MacroLibrary(viewModel.macros, viewModel.workflowId) }
    CompositionLocalProvider(
        LocalGeofencePlaces provides geofencePlaces,
        LocalNfcTags provides nfcTags,
        LocalMailAccounts provides mailAccounts,
        LocalSmartHome provides smartHome,
        LocalAiConnections provides aiConnections,
        LocalVariables provides variables,
        LocalMacros provides macros,
    ) {
        GraphEditorContent(viewModel = viewModel, onBack = onBack)
    }
}

@Composable
private fun GraphEditorContent(viewModel: GraphEditorViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val density = LocalDensity.current.density
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var showPalette by remember { mutableStateOf(false) }
    var showConfig by remember { mutableStateOf(false) }
    var hasAutoFitted by remember { mutableStateOf(false) }
    // Which of the bottom bar's surfaces is showing in place of the canvas, or null
    // for the canvas itself. It lives here rather than in the bar because the bar is
    // not what it swaps — see EditorBottomBar.
    var openTab by remember { mutableStateOf<EditorTab?>(null) }
    // Read here rather than inside the canvas: the cards and the wires both need
    // it, and it changes only when the graph does — which is already a recompose.
    val validation by viewModel.validation.collectAsState()

    // Back leaves selection mode before it leaves the editor — what the
    // contextual bar's ✕ does, from the system gesture. The overlays are each a
    // Dialog with its own window, so they still consume back ahead of this.
    BackHandler(enabled = state.selection.isNotEmpty) { viewModel.clearSelection() }
    // Registered second, so it outranks the one above: with a surface open the
    // canvas is not on screen, and back is a request to get back to it.
    BackHandler(enabled = openTab != null) { openTab = null }

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
        // Everything above the bottom bar, top bar included: a surface *replaces*
        // the editor's chrome rather than stacking under it, so it rises over the
        // whole screen bar the navigation items — the same gesture the full-screen
        // overlays make.
        //
        // Weighted, not fillMaxSize: the bottom bar below is a real child of this
        // Column, and a region that took every remaining pixel would measure it to
        // nothing.
        //
        // AnimatedContent, not a Box with an AnimatedVisibility over it, because it
        // drops the outgoing content once the transition ends — so at rest the
        // canvas is genuinely not composed while a surface is open, and no drag can
        // reach its gesture detectors through the panel. It also gives the three
        // surfaces one transition to share; see `surfaceTransition` for which way
        // each one moves and why.
        AnimatedContent(
            targetState = openTab,
            modifier = Modifier.weight(1f),
            label = stringResource(R.string.grapheditor_editor_surface),
            transitionSpec = { surfaceTransition() },
        ) { tab ->
            if (tab != null) {
                EditorTabPanel(
                    tab = tab,
                    workflow = state.workflow,
                    validation = viewModel.validation,
                    console = viewModel.console,
                    consoleMinLevel = viewModel.consoleMinLevel,
                    variableValues = viewModel.variableValues,
                    onMinLevelChange = { viewModel.setConsoleMinLevel(it) },
                    onClearConsole = { viewModel.clearConsole() },
                    onClose = { openTab = null },
                    // Picking a finding is a request to go and look at what it
                    // names, so it selects the node *and* brings the canvas back.
                    onSelectNode = { viewModel.selectNode(it); openTab = null },
                    onSelectConnection = { viewModel.selectConnection(it); openTab = null },
                    modifier = Modifier.fillMaxSize(),
                )
                return@AnimatedContent
            }
            Column(modifier = Modifier.fillMaxSize()) {
                EditorTopBar(
                    title = state.workflow.name,
                    nodeCount = state.workflow.nodes.size,
                    selectionLabel = state.selection
                        .takeIf { it.isNotEmpty }
                        ?.let { selectionText(selectionSummary(it)) },
                    selectionHasNodes = state.selection.nodeIds.isNotEmpty(),
                    canConfigure = state.selection.singleNodeId != null,
                    isMacroEnabled = state.isMacroEnabled,
                    icon = state.workflow.icon,
                    accent = state.workflow.accent,
                    onBack = onBack,
                    onToggleEnabled = { viewModel.setMacroEnabled(it) },
                    onEditMacro = { name, icon, accent -> viewModel.updateMacro(name, icon, accent) },
                    onDeleteWorkflow = { viewModel.deleteWorkflow(onDeleted = onBack) },
                    onClearSelection = { viewModel.clearSelection() },
                    onConfigure = { showConfig = true },
                    onDuplicateSelection = { viewModel.duplicateSelection() },
                    onDeleteSelection = { viewModel.deleteSelection() },
                )
                CanvasRegion(
                    state = state,
                    validation = validation,
                    viewModel = viewModel,
                    canvasSize = canvasSize,
                    density = density,
                    onCanvasSizeChange = { canvasSize = it },
                    onAddNode = { showPalette = true },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // Below the region above rather than over it, so it covers nothing and
        // steals no pan, and stays put and tappable whichever surface is showing.
        // It pads itself for the gesture bar, which is why nothing above it carries
        // `navigationBarsPadding()` any more.
        EditorBottomBar(
            selected = openTab,
            onSelect = { openTab = it },
            validation = viewModel.validation,
            consoleProblems = viewModel.consoleProblems,
        )
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
            title = stringResource(
                        R.string.grapheditor_connect_from,
                        originLabel(state.workflow, pick.from, rememberNodeText(), LocalContext.current),
                    ),
            restrictedTo = pick.suggestions.map { it.definition.typeId }.toSet(),
        )
    }

    if (showConfig) {
        val node = state.selection.singleNodeId?.let { state.workflow.node(it) }
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
            )
        } else {
            showConfig = false
        }
    }

}

/**
 * The graph itself, with the controls that only make sense over it.
 *
 * Its own composable so that opening one of [EditorBottomBar]'s surfaces removes
 * all of this from the composition in one move — the FABs and the zoom controls
 * belong to the canvas, not to the editor, and a Run button floating over the
 * console would be a button aimed at something you cannot see.
 */
@Composable
@Suppress("LongParameterList") // The canvas and its controls; every parameter is one of theirs.
private fun CanvasRegion(
    state: GraphEditorUiState,
    validation: GraphValidation,
    viewModel: GraphEditorViewModel,
    canvasSize: IntSize,
    density: Float,
    onCanvasSizeChange: (IntSize) -> Unit,
    onAddNode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        GraphCanvas(
            state = state,
            validation = validation,
            viewModel = viewModel,
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged(onCanvasSizeChange),
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
                .padding(start = 14.dp, bottom = 18.dp),
        )
        FloatingActionButton(
            onClick = onAddNode,
            containerColor = EditorColors.actionAccent,
            contentColor = EditorColors.textPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = 18.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.grapheditor_add_node))
        }
    }
}

private fun IntSize.centerPx(): Offset = Offset(width / 2f, height / 2f)

@Composable
private fun EmptyHint(modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.grapheditor_your_canvas_is_empty),
            color = EditorColors.textPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.grapheditor_tap_to_add_your_first),
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
                Icon(Icons.Filled.Add, contentDescription =
                    stringResource(R.string.grapheditor_zoom_in), tint = EditorColors.textPrimary)
            }
            Text(
                text = stringResource(R.string.grapheditor_zoom_percent, (scale * 100).roundToInt()),
                color = EditorColors.textSecondary,
                fontSize = 11.sp,
                modifier = Modifier
                    .clickable(onClick = onResetZoom)
                    .padding(vertical = 4.dp, horizontal = 6.dp),
            )
            IconButton(onClick = onZoomOut, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Remove, contentDescription =
                    stringResource(R.string.grapheditor_zoom_out), tint = EditorColors.textPrimary)
            }
            HorizontalDivider(
                modifier = Modifier.width(28.dp),
                color = EditorColors.chromeBorder,
            )
            IconButton(onClick = onFit, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.FitScreen, contentDescription =
                    stringResource(R.string.grapheditor_fit_to_screen), tint = EditorColors.textPrimary)
            }
        }
    }
}

/**
 * "Node name › Port label" for the port a connection drag started from.
 *
 * Takes the [nodeText] rather than reading it, because this is a plain function
 * called from two places and one of them is not a composable.
 */
private fun originLabel(
    workflow: com.example.ottomatic.domain.model.Workflow,
    ref: PortRef,
    nodeText: NodeText,
    context: android.content.Context,
): String {
    val node = workflow.node(ref.nodeId) ?: return ref.portName.value
    val portLabel = resolvePort(workflow, ref)
        ?.let { nodeText.portLabel(node.typeId, it) }
        ?: ref.portName.value
    return context.getString(R.string.grapheditor_port_path, node.name, portLabel)
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
    nodeText: NodeText,
    context: android.content.Context,
): String? = workflow.incomingData(nodeId, port).firstOrNull()?.let { connection ->
    originLabel(
        workflow,
        PortRef(
            nodeId = connection.fromNodeId,
            portName = connection.fromPort,
            isOutput = true,
            kind = PortKind.DATA,
        ),
        nodeText,
        context,
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
) {
    val definition = NodeTypeRegistry.byId(node.typeId)
    val schema = definition?.let { effectiveConfigSchema(it, workflow, node) }
    val dataInputPorts = definition?.let { effectiveInputPorts(it, workflow, node) }
        ?.filter { it.kind == PortKind.DATA }
        .orEmpty()
    // A config key and its DATA port name are the same string by construction
    // (see WorkflowNode.config), which is what lets each field carry its own
    // wiring toggle instead of a detached list at the bottom of the sheet.
    //
    // A port with *no* config key is not listed here at all. It has no form value
    // to choose between, so there is nothing to toggle: `visibleInputPorts` shows
    // it on the card unconditionally, and a switch that could not hide it would
    // only ever delete the edge wired into it — which the canvas already does,
    // visibly.
    val portByKey = dataInputPorts.associateBy { ConfigKey(it.name.value) }
    // One wirable field puts every field in this sheet on the narrower measure, so
    // they keep a common right edge rather than the wirable ones looking clipped.
    val gutter = schemaKeys(schema).any { it in portByKey }
    // The form grows without bound — a script alone can declare sixteen ports —
    // so it gets a full screen to scroll in.
    EditorOverlay(title = stringResource(R.string.grapheditor_configure), onClose = onDismiss) { _ ->
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
                text = definition?.let { rememberNodeText().name(it) } ?: node.typeId.value,
                color = EditorColors.textSecondary,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.height(12.dp))
            NodePermissionNotice(definition = definition, node = node)
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = node.name,
                onValueChange = onNameChange,
                label = { Text(stringResource(R.string.grapheditor_name)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = if (gutter) ConfigFieldToggleGutter else 0.dp),
            )
            if (schema != null) {
                Spacer(modifier = Modifier.height(12.dp))
                val nodeText = rememberNodeText()
                val context = LocalContext.current
                schema.fields.forEach { field ->
                    val port = portByKey[field.key]
                    ConfigFieldRow(
                        typeId = node.typeId,
                        field = field,
                        port = port,
                        wired = port != null && port.name in node.visibleDataInputs,
                        sourceLabel = port?.let { dataSourceLabel(workflow, node.id, it.name, nodeText, context) },
                        value = node.config[field.key] ?: field.defaultValue,
                        reserveToggleGutter = gutter,
                        onValueChange = { onConfigChange(field.key, it) },
                        onWiredChange = { port?.let { p -> onDataInputVisibilityChange(p.name, it) } },
                        // A field whose editor depends on a neighbour reads it from
                        // here; `@MailFolder` is the only one so far, and it needs
                        // the account chosen beside it to know which server to ask.
                        siblingValue = { key -> node.config[key].orEmpty() },
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
                ConfigFormHint(node)
            }
        }
    }
}
