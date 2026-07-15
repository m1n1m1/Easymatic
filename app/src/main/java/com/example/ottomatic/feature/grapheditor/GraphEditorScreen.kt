package com.example.ottomatic.feature.grapheditor

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.NodeTypeDefinition
import com.example.ottomatic.domain.registry.NodeTypeRegistry
import kotlin.math.roundToInt

@Composable
fun GraphEditorScreen(viewModel: GraphEditorViewModel) {
    val state by viewModel.uiState.collectAsState()
    val density = LocalDensity.current.density
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var showPalette by remember { mutableStateOf(false) }
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
}

private fun IntSize.centerPx(): Offset = Offset(width / 2f, height / 2f)

@Composable
private fun EditorTopBar(
    title: String,
    nodeCount: Int,
    hasSelection: Boolean,
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
            if (hasSelection) {
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = EditorColors.chrome,
    ) {
        LazyColumn(
            modifier = Modifier.padding(bottom = 24.dp),
        ) {
            item { PaletteHeader("Triggers") }
            items(NodeTypeRegistry.byKind(NodeKind.TRIGGER), key = { it.typeId }) { definition ->
                PaletteRow(definition, onPick)
            }
            item { PaletteHeader("Actions") }
            items(NodeTypeRegistry.byKind(NodeKind.ACTION), key = { it.typeId }) { definition ->
                PaletteRow(definition, onPick)
            }
        }
    }
}

@Composable
private fun PaletteHeader(text: String) {
    Text(
        text = text,
        color = EditorColors.textSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 6.dp),
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
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = nodeIcon(definition.iconKey),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp),
            )
        }
        Column {
            Text(
                text = definition.displayName,
                color = EditorColors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = definition.description,
                color = EditorColors.textSecondary,
                fontSize = 12.sp,
            )
        }
    }
}
