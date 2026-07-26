package com.example.ottomatic.feature.grapheditor

import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.ConfigKey
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.NodeTypeDefinition
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.engine.trigger.GeofenceTrigger
import com.example.ottomatic.feature.geofence.LocalGeofencePlaces
import com.example.ottomatic.domain.registry.effectiveInputPorts
import com.example.ottomatic.domain.registry.effectiveOutputPorts
import kotlin.math.roundToInt

private val NodeShape = RoundedCornerShape(14.dp)
private const val PORT_HANDLE_SIZE = 26f

@Composable
fun NodeCard(
    node: WorkflowNode,
    definition: NodeTypeDefinition,
    workflow: Workflow,
    isSelected: Boolean,
    hoverPort: PortRef?,
    revealedLabel: PortRef?,
    pendingFrom: PortRef?,
    onSelect: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onPortDragStart: (PortRef) -> Unit,
    onPortDrag: (Offset) -> Unit,
    onPortDragEnd: () -> Unit,
    onPortDragCancel: () -> Unit,
    onToggleRevealedLabel: (PortRef) -> Unit,
) {
    val density = LocalDensity.current.density
    val layoutInputPorts = effectiveInputPorts(definition, workflow, node)
    val visibleInputPorts = visibleInputPorts(definition, workflow, node)
    val outputPorts = effectiveOutputPorts(definition, workflow, node)
    val width = GraphGeometry.nodeWidth(layoutInputPorts.size, outputPorts.size)
    val labelToShow =
        pendingFrom?.takeIf { it.nodeId == node.id && it.isOutput } ?: revealedLabel

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
        OutputLabels(node.id, outputPorts, width, density, labelToShow)
        Ports(
            node = node,
            layoutInputPorts = layoutInputPorts,
            visibleInputPorts = visibleInputPorts,
            outputPorts = outputPorts,
            width = width,
            hoverPort = hoverPort,
            density = density,
            onPortDragStart = onPortDragStart,
            onPortDrag = onPortDrag,
            onPortDragEnd = onPortDragEnd,
            onPortDragCancel = onPortDragCancel,
            onToggleRevealedLabel = onToggleRevealedLabel,
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
                    imageVector = nodeIcon(definition.icon),
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = nodeSubtitle(node, definition),
                        color = EditorColors.textSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ConditionBadge(count = node.conditions.size)
                }
            }
        }
    }
}

/**
 * The line under a node's name. Normally its kind ("Trigger", "Action"), but a
 * geofence trigger names the place it watches instead.
 *
 * That node is the one case where the kind word carries no information the icon
 * and accent colour have not already given, while *which place* is the entire
 * difference between two otherwise identical cards — and, unlike coordinates,
 * a place name fits.
 */
@Composable
private fun nodeSubtitle(node: WorkflowNode, definition: NodeTypeDefinition): String {
    if (node.typeId != GeofenceTrigger.TYPE_ID) return kindLabel(definition.kind)
    val placeId = node.config[ConfigKey("placeId")].orEmpty()
    val library = LocalGeofencePlaces.current
    return when {
        placeId.isBlank() -> "No place selected"
        else -> library?.placeById(placeId)?.name ?: "Place missing"
    }
}

/**
 * Marks a node as gated by attached conditions.
 *
 * Deliberately a badge rather than a chip per condition: the card is a fixed
 * [GraphGeometry.NODE_HEIGHT] that the port maths depends on, so the canvas shows
 * only *that* the node is gated and how many times. What the conditions actually
 * say belongs in the config sheet, where there is room to edit them.
 */
@Composable
private fun ConditionBadge(count: Int) {
    if (count == 0) return
    Row(
        modifier = Modifier
            .padding(start = 6.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(EditorColors.conditionAccent.copy(alpha = 0.18f))
            .padding(horizontal = 4.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.FilterAlt,
            contentDescription = "Conditions",
            tint = EditorColors.conditionAccent,
            modifier = Modifier.size(11.dp),
        )
        if (count > 1) {
            Text(
                text = count.toString(),
                color = EditorColors.conditionAccent,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

@OptIn(ExperimentalTextApi::class)
@Composable
private fun OutputLabels(
    nodeId: NodeId,
    outputPorts: List<Port>,
    width: Float,
    density: Float,
    revealedLabel: PortRef?,
) {
    if (outputPorts.size < 2) return
    val revealedPort = revealedLabel
        ?.takeIf { it.nodeId == nodeId && it.isOutput }
        ?.let { ref -> outputPorts.firstOrNull { it.name == ref.portName && it.kind == ref.kind } }
        ?: return
    val portOffset = GraphGeometry.portOffset(emptyList(), outputPorts, width, revealedPort)
    val textMeasurer = rememberTextMeasurer()
    val layout = remember(revealedPort.label) {
        textMeasurer.measure(
            AnnotatedString(revealedPort.label),
            style = TextStyle(fontSize = 10.sp, color = EditorColors.textSecondary),
        )
    }
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (portOffset.x * density - layout.size.width / 2f).roundToInt(),
                    ((portOffset.y + 10f) * density).roundToInt(),
                )
            }
            .size(width = (layout.size.width / density).dp, height = (layout.size.height / density).dp)
            .drawBehind { drawText(layout) },
    )
}

@Composable
private fun Ports(
    node: WorkflowNode,
    layoutInputPorts: List<Port>,
    visibleInputPorts: List<Port>,
    outputPorts: List<Port>,
    width: Float,
    hoverPort: PortRef?,
    density: Float,
    onPortDragStart: (PortRef) -> Unit,
    onPortDrag: (Offset) -> Unit,
    onPortDragEnd: () -> Unit,
    onPortDragCancel: () -> Unit,
    onToggleRevealedLabel: (PortRef) -> Unit,
) {
    visibleInputPorts.forEach { port ->
        PortHandle(
            ref = PortRef(node.id, port.name, isOutput = false, port.kind),
            port = port,
            center = GraphGeometry.portOffset(layoutInputPorts, outputPorts, width, port),
            isSnapTarget = hoverPort == PortRef(node.id, port.name, isOutput = false, port.kind),
            density = density,
            onDragStart = onPortDragStart,
            onDrag = onPortDrag,
            onDragEnd = onPortDragEnd,
            onDragCancel = onPortDragCancel,
        )
    }
    outputPorts.forEach { port ->
        PortHandle(
            ref = PortRef(node.id, port.name, isOutput = true, port.kind),
            port = port,
            center = GraphGeometry.portOffset(layoutInputPorts, outputPorts, width, port),
            isSnapTarget = hoverPort == PortRef(node.id, port.name, isOutput = true, port.kind),
            density = density,
            onDragStart = onPortDragStart,
            onDrag = onPortDrag,
            onDragEnd = onPortDragEnd,
            onDragCancel = onPortDragCancel,
            onTap = { onToggleRevealedLabel(PortRef(node.id, port.name, isOutput = true, port.kind)) },
        )
    }
}

@Composable
private fun PortHandle(
    ref: PortRef,
    port: Port,
    center: Offset,
    isSnapTarget: Boolean,
    density: Float,
    onDragStart: (PortRef) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onTap: (() -> Unit)? = null,
) {
    val half = PORT_HANDLE_SIZE / 2f
    val ring = portColor(port, isSnapTarget)
    val fill = if (isSnapTarget) EditorColors.portSnap else EditorColors.canvasBackground
    val tapModifier = if (onTap != null) {
        Modifier.pointerInput(ref) {
            detectTapGestures(onTap = { onTap() })
        }
    } else {
        Modifier
    }
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
            }
            .then(tapModifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size((GraphGeometry.PORT_RADIUS * 2).dp)
                .clip(CircleShape)
                .background(fill)
                .border(2.dp, ring, CircleShape),
        )
    }
}

private fun portColor(port: Port, isSnapTarget: Boolean): Color =
    if (isSnapTarget) EditorColors.portSnap else when (port.kind) {
        PortKind.EXECUTION -> EditorColors.execPort
        PortKind.DATA -> portTypeColor(port.schema)
    }
