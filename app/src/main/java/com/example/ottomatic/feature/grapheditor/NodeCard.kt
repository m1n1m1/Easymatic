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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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

/** Fully rounded: the label reads as a pill, not a second, smaller card. */
private val LabelShape = RoundedCornerShape(percent = 50)
private val LabelTextStyle = TextStyle(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium)
private val LABEL_PADDING_H = 9.dp
private val LABEL_PADDING_V = 3.dp

/** Distance in graph units between a port handle and the near edge of its label. */
private const val LABEL_GAP = 10f

/**
 * The port colour is a saturated accent, so it tints the pill rather than
 * filling it — composited over the opaque node background it stays legible
 * while the border and text carry the actual hue.
 */
private const val LABEL_FILL_ALPHA = 0.18f
private const val LABEL_BORDER_ALPHA = 0.55f

/** Half-strength selected border for a node a marquee is currently over. */
private const val CANDIDATE_BORDER_ALPHA = 0.5f

@Composable
fun NodeCard(
    node: WorkflowNode,
    definition: NodeTypeDefinition,
    workflow: Workflow,
    highlight: NodeHighlight,
    hoverPort: PortRef?,
    revealedLabel: PortRef?,
    pendingFrom: PortRef?,
    gestures: NodeGestureHandlers,
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
    val labelToShow = pendingFrom?.takeIf { it.nodeId == node.id } ?: revealedLabel

    Box(
        modifier = Modifier
            .offset { IntOffset((node.x * density).roundToInt(), (node.y * density).roundToInt()) }
            .size(width.dp, GraphGeometry.NODE_HEIGHT.dp)
            // A name chip overflows the card bounds, so a labelled node has to
            // outrank both plain and selected neighbours or the chip gets covered.
            .zIndex(
                when {
                    labelToShow?.nodeId == node.id -> 2f
                    highlight == NodeHighlight.SELECTED -> 1f
                    else -> 0f
                },
            ),
    ) {
        NodeBody(
            node = node,
            definition = definition,
            highlight = highlight,
            gestures = gestures,
        )
        PortLabel(node.id, layoutInputPorts, outputPorts, width, density, labelToShow)
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
    highlight: NodeHighlight,
    gestures: NodeGestureHandlers,
) {
    val accent = accentColor(definition.kind)
    // A marquee candidate wears the selected border at half strength: enough to
    // read as "this one is coming with you", not enough to be mistaken for a
    // selection that has already happened.
    val borderColor = when (highlight) {
        NodeHighlight.SELECTED -> EditorColors.nodeSelectedBorder
        NodeHighlight.CANDIDATE -> EditorColors.nodeSelectedBorder.copy(alpha = CANDIDATE_BORDER_ALPHA)
        NodeHighlight.NONE -> EditorColors.nodeBorder
    }
    val borderWidth = if (highlight == NodeHighlight.NONE) 1.dp else 2.dp
    Box(
        modifier = Modifier
            .fillMaxSize()
            .shadow(elevation = 8.dp, shape = NodeShape, clip = false)
            .clip(NodeShape)
            .background(EditorColors.nodeBackground)
            .border(borderWidth, borderColor, NodeShape)
            // One detector, not a tap/drag pair: press, tap, long press and drag
            // are outcomes of the same finger and have to be arbitrated together.
            .pointerInput(node.id) { detectNodeGestures(gestures) },
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
                Text(
                    text = nodeSubtitle(node, definition),
                    color = EditorColors.textSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
 * The name of the currently revealed port, drawn as a pill beside its handle.
 *
 * The opaque fill is not decoration: a port's bezier leaves it travelling
 * straight down, so bare text centred on the port sits directly *on* the wire.
 * The pill is what makes the name readable over the graph behind it, and it
 * wears the port's own colour so the name and the handle it belongs to read as
 * one object even when several ports sit side by side.
 *
 * Outputs hang below the card, inputs float above it — the gap to the handle is
 * the same either way, measured from the pill's near edge.
 */
@Composable
private fun PortLabel(
    nodeId: NodeId,
    inputPorts: List<Port>,
    outputPorts: List<Port>,
    width: Float,
    density: Float,
    revealedLabel: PortRef?,
) {
    val ref = revealedLabel?.takeIf { it.nodeId == nodeId } ?: return
    val ports = if (ref.isOutput) outputPorts else inputPorts
    val port = ports.firstOrNull { it.name == ref.portName && it.kind == ref.kind } ?: return

    val portOffset = GraphGeometry.portOffset(inputPorts, outputPorts, width, port)
    val accent = portColor(port, isSnapTarget = false)

    Box(
        modifier = Modifier
            // Placed from the measured pill rather than an estimated size: the
            // input side anchors its *bottom* edge, so guessing the height would
            // slide the pill down over the handle.
            .layout { measurable, _ ->
                val placeable = measurable.measure(Constraints())
                layout(0, 0) {
                    val x = portOffset.x * density - placeable.width / 2f
                    val y = if (ref.isOutput) {
                        (portOffset.y + LABEL_GAP) * density
                    } else {
                        (portOffset.y - LABEL_GAP) * density - placeable.height
                    }
                    placeable.place(x.roundToInt(), y.roundToInt())
                }
            }
            .zIndex(1f)
            .shadow(elevation = 6.dp, shape = LabelShape, clip = false)
            .clip(LabelShape)
            .background(accent.copy(alpha = LABEL_FILL_ALPHA).compositeOver(EditorColors.nodeBackground))
            .border(1.dp, accent.copy(alpha = LABEL_BORDER_ALPHA), LabelShape)
            .padding(horizontal = LABEL_PADDING_H, vertical = LABEL_PADDING_V),
    ) {
        Text(
            text = port.label,
            color = accent,
            style = LabelTextStyle,
            maxLines = 1,
        )
    }
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
            onTap = { onToggleRevealedLabel(PortRef(node.id, port.name, isOutput = false, port.kind)) },
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
