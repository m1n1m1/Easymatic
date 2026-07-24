package com.example.ottomatic.feature.grapheditor

import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.ConfigKey
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeTypeDefinition
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.registry.NodeTypeRegistry
import com.example.ottomatic.domain.registry.effectiveConfigSchema
import com.example.ottomatic.domain.registry.effectiveInputPorts
import kotlin.math.roundToInt

@Composable
fun GraphEditorScreen(
    viewModel: GraphEditorViewModel,
    showBatteryPrompt: Boolean = false,
    onDismissBatteryPrompt: () -> Unit = {},
    onConfirmBatteryPrompt: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val density = LocalDensity.current.density
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var showPalette by remember { mutableStateOf(false) }
    var showConfig by remember { mutableStateOf(false) }
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
        NodePaletteSheet(
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

    if (showConfig) {
        val selection = state.selection
        val node = (selection as? Selection.Node)?.let { ref ->
            state.workflow.node(ref.nodeId)
        }
        if (node != null) {
            NodeConfigSheet(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodePaletteSheet(
    onDismiss: () -> Unit,
    onPick: (NodeTypeDefinition) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var collapsedCategories by remember { mutableStateOf(emptySet<NodeCategory>()) }
    val searchTerm = query.trim()
    val searching = searchTerm.isNotEmpty()
    val matchingDefinitions = NodeTypeRegistry.all.filter { definition ->
        searchTerm.isEmpty() || listOf(
            definition.displayName,
            definition.description,
            definition.typeId.value,
            definition.category.displayName,
        ).any { it.contains(searchTerm, ignoreCase = true) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search nodes") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (searching) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            )
            LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
                if (matchingDefinitions.isEmpty()) {
                    item {
                        Text(
                            text = "No nodes match your search",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                } else {
                    listOf(NodeKind.TRIGGER, NodeKind.ACTION).forEach { kind ->
                        val categoryGroups = NodeTypeRegistry.categoriesFor(kind).map { category ->
                            category to matchingDefinitions.filter { it.category == category }
                        }.filter { (_, definitions) -> definitions.isNotEmpty() }
                        if (categoryGroups.isNotEmpty()) {
                            item(key = "kind-${kind.name}") {
                                PaletteHeader(if (kind == NodeKind.TRIGGER) "Triggers" else "Actions")
                            }
                            categoryGroups.forEach { (category, definitions) ->
                                val isCollapsed = !searching && category in collapsedCategories
                                item(key = "category-${category.name}") {
                                    val chevronRotation by animateFloatAsState(
                                        targetValue = if (isCollapsed) 0f else 180f,
                                        animationSpec = tween(220),
                                        label = "chevronRotation",
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                collapsedCategories = if (category in collapsedCategories) {
                                                    collapsedCategories - category
                                                } else {
                                                    collapsedCategories + category
                                                }
                                            }
                                            .padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = category.displayName,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            text = definitions.size.toString(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(end = 8.dp),
                                        )
                                        Icon(
                                            imageVector = Icons.Filled.ExpandMore,
                                            contentDescription = if (isCollapsed) {
                                                "Expand ${category.displayName}"
                                            } else {
                                                "Collapse ${category.displayName}"
                                            },
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.rotate(chevronRotation),
                                        )
                                    }
                                    AnimatedVisibility(
                                        visible = !isCollapsed,
                                        enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                                        exit = shrinkVertically(tween(220)) + fadeOut(tween(180)),
                                    ) {
                                        Column {
                                            definitions.forEach { definition ->
                                                PaletteRow(definition, onPick)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun PaletteRow(
    definition: NodeTypeDefinition,
    onPick: (NodeTypeDefinition) -> Unit,
) {
    val accent = accentColor(definition.kind)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick(definition) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = nodeIcon(definition.icon),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp),
            )
        }
        Column {
            Text(
                text = definition.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = definition.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodeConfigSheet(
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = EditorColors.chrome,
    ) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text(
                text = "Configure",
                color = EditorColors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = definition?.displayName ?: node.typeId.value,
                color = EditorColors.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = node.name,
                onValueChange = onNameChange,
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (schema != null) {
                Spacer(modifier = Modifier.height(12.dp))
                schema.fields.forEach { field ->
                    ConfigFieldEditor(
                        field = field,
                        value = node.config[field.key] ?: field.defaultValue,
                        onValueChange = { onConfigChange(field.key, it) },
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
            if (dataInputPorts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Data inputs",
                    color = EditorColors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                dataInputPorts.forEach { port ->
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

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("CyclomaticComplexMethod") // Inherent: one branch per ConfigFieldType.
@Composable
private fun ConfigFieldEditor(
    field: com.example.ottomatic.domain.registry.ConfigField<*>,
    value: String,
    onValueChange: (String) -> Unit,
) {
    val type = field.type
    var expanded by remember { mutableStateOf(false) }
    Column {
        when (type) {
        is com.example.ottomatic.domain.registry.ConfigFieldType.ENUM -> {
            val selected = type.options.firstOrNull { it.value == value }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                OutlinedTextField(
                    value = selected?.label ?: value,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(field.label) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                )
                androidx.compose.material3.DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    type.options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                onValueChange(option.value)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
        com.example.ottomatic.domain.registry.ConfigFieldType.MULTILINE -> {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(field.label) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
        }
        com.example.ottomatic.domain.registry.ConfigFieldType.INT -> {
            OutlinedTextField(
                value = value,
                onValueChange = { new ->
                    if (new.matches(Regex("-?\\d*")) || new.isEmpty()) onValueChange(new)
                },
                label = { Text(field.label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        com.example.ottomatic.domain.registry.ConfigFieldType.DOUBLE -> {
            OutlinedTextField(
                value = value,
                onValueChange = { new ->
                    if (new.matches(Regex("-?\\d*\\.?\\d*")) || new.isEmpty()) onValueChange(new)
                },
                label = { Text(field.label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        com.example.ottomatic.domain.registry.ConfigFieldType.BOOL -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                androidx.compose.material3.Switch(
                    checked = value.toBooleanStrictOrNull() == true,
                    onCheckedChange = { onValueChange(it.toString()) },
                )
                Text(
                    text = field.label,
                    color = EditorColors.textPrimary,
                    fontSize = 14.sp,
                )
            }
        }
        com.example.ottomatic.domain.registry.ConfigFieldType.STR -> {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(field.label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    }
}
