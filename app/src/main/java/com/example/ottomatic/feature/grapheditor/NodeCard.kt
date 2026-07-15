package com.example.ottomatic.feature.grapheditor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.NodeTypeDefinition
import com.example.ottomatic.domain.model.WorkflowNode
import kotlin.math.roundToInt

private val NodeShape = RoundedCornerShape(14.dp)
private const val PORT_HANDLE_SIZE = 26f

@Composable
fun NodeCard(
    node: WorkflowNode,
    definition: NodeTypeDefinition,
    isSelected: Boolean,
    hoverPort: PortRef?,
    onSelect: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onPortDragStart: (PortRef) -> Unit,
    onPortDrag: (Offset) -> Unit,
    onPortDragEnd: () -> Unit,
    onPortDragCancel: () -> Unit,
) {
    val density = LocalDensity.current.density
    val width = GraphGeometry.nodeWidth(definition)

    Box(
        modifier = Modifier
            .offset { IntOffset((node.x * density).roundToInt(), (node.y * density).roundToInt()) }
            .size(width.dp, GraphGeometry.NODE_HEIGHT.dp)
            .zIndex(if (isSelected) 1f else 0f),
    ) {
        NodeBody(
            node = node,
            definition = definition,
            isSelected = isSelected,
            onSelect = onSelect,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
            density = density,
        )
        OutputLabels(definition, density)
        Ports(
            node = node,
            definition = definition,
            hoverPort = hoverPort,
            density = density,
            onPortDragStart = onPortDragStart,
            onPortDrag = onPortDrag,
            onPortDragEnd = onPortDragEnd,
            onPortDragCancel = onPortDragCancel,
        )
    }
}

@Composable
private fun NodeBody(
    node: WorkflowNode,
    definition: NodeTypeDefinition,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    density: Float,
) {
    val accent = accentColor(definition.kind)
    val borderColor = if (isSelected) EditorColors.nodeSelectedBorder else EditorColors.nodeBorder
    val borderWidth = if (isSelected) 2.dp else 1.dp
    Box(
        modifier = Modifier
            .fillMaxSize()
            .shadow(elevation = 8.dp, shape = NodeShape, clip = false)
            .clip(NodeShape)
            .background(EditorColors.nodeBackground)
            .border(borderWidth, borderColor, NodeShape)
            .pointerInput(node.id) {
                detectTapGestures(onTap = { onSelect() })
            }
            .pointerInput(node.id) {
                detectDragGestures(
                    onDragStart = { onSelect() },
                    onDrag = { change, amount ->
                        change.consume()
                        onDrag(Offset(amount.x / density, amount.y / density))
                    },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = nodeIcon(definition.iconKey),
                    contentDescription = definition.displayName,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(
                modifier = Modifier.padding(start = 10.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = node.name,
                    color = EditorColors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (definition.kind == NodeKind.TRIGGER) "Trigger" else "Action",
                    color = EditorColors.textSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun OutputLabels(definition: NodeTypeDefinition, density: Float) {
    if (definition.outputPorts.size < 2) return
    definition.outputPorts.forEachIndexed { index, label ->
        val port = GraphGeometry.portOffset(definition, index, isOutput = true)
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        ((port.x - 35f) * density).roundToInt(),
                        ((port.y + 10f) * density).roundToInt(),
                    )
                }
                .width(70.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = EditorColors.textSecondary,
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Ports(
    node: WorkflowNode,
    definition: NodeTypeDefinition,
    hoverPort: PortRef?,
    density: Float,
    onPortDragStart: (PortRef) -> Unit,
    onPortDrag: (Offset) -> Unit,
    onPortDragEnd: () -> Unit,
    onPortDragCancel: () -> Unit,
) {
    definition.inputPorts.forEachIndexed { index, _ ->
        PortHandle(
            ref = PortRef(node.id, index, isOutput = false),
            center = GraphGeometry.portOffset(definition, index, isOutput = false),
            isSnapTarget = hoverPort == PortRef(node.id, index, isOutput = false),
            density = density,
            onDragStart = onPortDragStart,
            onDrag = onPortDrag,
            onDragEnd = onPortDragEnd,
            onDragCancel = onPortDragCancel,
        )
    }
    definition.outputPorts.forEachIndexed { index, _ ->
        PortHandle(
            ref = PortRef(node.id, index, isOutput = true),
            center = GraphGeometry.portOffset(definition, index, isOutput = true),
            isSnapTarget = hoverPort == PortRef(node.id, index, isOutput = true),
            density = density,
            onDragStart = onPortDragStart,
            onDrag = onPortDrag,
            onDragEnd = onPortDragEnd,
            onDragCancel = onPortDragCancel,
        )
    }
}

@Composable
private fun PortHandle(
    ref: PortRef,
    center: Offset,
    isSnapTarget: Boolean,
    density: Float,
    onDragStart: (PortRef) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val half = PORT_HANDLE_SIZE / 2f
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    ((center.x - half) * density).roundToInt(),
                    ((center.y - half) * density).roundToInt(),
                )
            }
            .size(PORT_HANDLE_SIZE.dp)
            .pointerInput(ref) {
                detectDragGestures(
                    onDragStart = { onDragStart(ref) },
                    onDrag = { change, amount ->
                        change.consume()
                        onDrag(Offset(amount.x / density, amount.y / density))
                    },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragCancel,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val fill = if (isSnapTarget) EditorColors.portSnap else EditorColors.canvasBackground
        val ring = if (isSnapTarget) EditorColors.portSnap else EditorColors.port
        Box(
            modifier = Modifier
                .size((GraphGeometry.PORT_RADIUS * 2).dp)
                .clip(CircleShape)
                .background(fill)
                .border(2.dp, ring, CircleShape),
        )
    }
}
