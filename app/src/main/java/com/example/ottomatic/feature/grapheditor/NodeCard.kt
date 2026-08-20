// The one card, drawn in parts: its body, its badge, its subtitle, its run
// button, its port handles and their labels. Splitting them across files would
// separate things that share the card's geometry and are only ever read together.
@file:Suppress("TooManyFunctions")

package com.example.ottomatic.feature.grapheditor

import androidx.compose.ui.res.stringResource
import com.example.ottomatic.R
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.ConfigKey
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
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
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.engine.validation.Severity
import com.example.ottomatic.feature.i18n.rememberNodeText
import kotlin.math.roundToInt

private val NodeShape = RoundedCornerShape(14.dp)
private const val PORT_HANDLE_SIZE = 26f

/** Matches the icon chip across the row from it; `RUN_BUTTON_EXTRA` is this plus its gap. */
private val RUN_BUTTON_SIZE = 34.dp
private val RUN_ICON_SIZE = 18.dp
private const val RUN_BUTTON_FILL_ALPHA = 0.16f

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
    /**
     * The worst thing [com.example.ottomatic.engine.validation.GraphValidator] says
     * about this node, or null when it says nothing.
     *
     * A parameter rather than more [NodeHighlight] cases, because validity is
     * orthogonal to selection: a node can be selected *and* broken, and the card
     * has to show both. Folding them into one enum would force a precedence and
     * throw the loser away. A [Severity] is an enum, so the card stays skippable —
     * the whole `GraphValidation` never reaches this leaf.
     */
    problem: Severity?,
    /**
     * Whether this node has a run in flight — only ever true for the node that
     * owns [onRunToggle].
     *
     * A `Boolean` and a nullable lambda rather than richer state, for the reason
     * [problem] is a [Severity] rather than the whole `GraphValidation`: the card
     * has to stay skippable, and every node on the canvas is handed these.
     */
    isRunning: Boolean,
    /**
     * Starts this node's run, or stops the one already going, or null for a node
     * that has no such button — which is every node but `trigger.manual`.
     */
    onRunToggle: (() -> Unit)?,
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
    val width = GraphGeometry.nodeWidth(node.typeId, layoutInputPorts.size, outputPorts.size)
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
            problem = problem,
            isRunning = isRunning,
            onRunToggle = onRunToggle,
            gestures = gestures,
        )
        PortLabel(node.id, node.typeId, layoutInputPorts, outputPorts, width, density, labelToShow)
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
    problem: Severity?,
    isRunning: Boolean,
    onRunToggle: (() -> Unit)?,
    gestures: NodeGestureHandlers,
) {
    val accent = accentColor(definition.kind)
    // A marquee candidate wears the selected border at half strength: enough to
    // read as "this one is coming with you", not enough to be mistaken for a
    // selection that has already happened.
    //
    // A problem tints the border only while the node is *not* selected: selection
    // is a thing the user is doing right now and has to stay legible, and the badge
    // keeps saying "broken" underneath it either way.
    val borderColor = when (highlight) {
        NodeHighlight.SELECTED -> EditorColors.nodeSelectedBorder
        NodeHighlight.CANDIDATE -> EditorColors.nodeSelectedBorder.copy(alpha = CANDIDATE_BORDER_ALPHA)
        NodeHighlight.NONE -> problemColor(problem) ?: EditorColors.nodeBorder
    }
    val borderWidth = if (highlight == NodeHighlight.NONE && problem == null) 1.dp else 2.dp
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
            // fillMaxWidth so the name below can take a weight and ellipsize
            // *before* the run button rather than sliding under it. On a card
            // with no button there is nothing to reserve for and nothing moves.
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
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
                    contentDescription = rememberNodeText().name(definition),
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f).padding(start = 10.dp),
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
            if (onRunToggle != null) RunButton(node.id, accent, isRunning, onRunToggle)
        }
        ProblemBadge(problem, Modifier.align(Alignment.TopEnd))
    }
}

/**
 * The run button a `trigger.manual` card carries.
 *
 * The tap is taken with [detectTapGestures] rather than `clickable`, and that is
 * the whole reason this works: the card arbitrates press, tap, long press and
 * drag in one detector whose first line is
 * `awaitFirstDown(requireUnconsumed = true)`, so a child that consumes its own
 * down makes the card skip the gesture entirely. It is exactly how [PortHandle]
 * already coexists with node drag, and it costs the same two things — a drag
 * begun on the button does not move the node, and the target shrinks with the
 * zoom.
 *
 * Sized and tinted as the icon chip opposite it, because they are the two ends
 * of the same row and a button that out-shouted the node's own identity would
 * make the card read as a control rather than as a step.
 */
@Composable
private fun RunButton(nodeId: NodeId, accent: Color, isRunning: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .size(RUN_BUTTON_SIZE)
            .clip(CircleShape)
            .background(accent.copy(alpha = RUN_BUTTON_FILL_ALPHA))
            .pointerInput(nodeId) { detectTapGestures { onToggle() } },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            contentDescription = stringResource(
                if (isRunning) R.string.grapheditor_stop_workflow else R.string.grapheditor_run_workflow,
            ),
            tint = accent,
            modifier = Modifier.size(RUN_ICON_SIZE),
        )
    }
}

/**
 * The mark on a card that something is wrong with it.
 *
 * Inside the card's bounds rather than offset over its corner: a chip that
 * overflows has to out-`zIndex` its neighbours to stay visible, and the card
 * already spends that budget on [PortLabel]. Two signals, not one — the badge is
 * unreadable when the canvas is zoomed out, and the border tint is what survives.
 */
@Composable
private fun BoxScope.ProblemBadge(problem: Severity?, modifier: Modifier = Modifier) {
    val color = problemColor(problem) ?: return
    Icon(
        imageVector = if (problem == Severity.ERROR) Icons.Filled.ErrorOutline else Icons.Filled.WarningAmber,
        contentDescription = stringResource(
            if (problem == Severity.ERROR) R.string.grapheditor_has_error else R.string.grapheditor_has_warning,
        ),
        tint = color,
        modifier = modifier
            .padding(top = 6.dp, end = 6.dp)
            .size(14.dp),
    )
}

private fun problemColor(problem: Severity?): Color? = when (problem) {
    Severity.ERROR -> EditorColors.errorAccent
    Severity.WARNING -> EditorColors.warnAccent
    null -> null
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
    if (node.typeId != GeofenceTrigger.TYPE_ID) return stringResource(kindLabelRes(definition.kind))
    val placeId = node.config[ConfigKey("placeId")].orEmpty()
    val library = LocalGeofencePlaces.current
    return when {
        placeId.isBlank() -> stringResource(R.string.grapheditor_no_place_selected)
        else -> library?.placeById(placeId)?.name ?: stringResource(R.string.grapheditor_place_missing)
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
    typeId: NodeTypeId,
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
            text = rememberNodeText().portLabel(typeId, port),
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
        val shape = portShape(port)
        Box(
            modifier = Modifier
                .size((GraphGeometry.PORT_RADIUS * 2).dp)
                .clip(shape)
                .background(fill)
                .border(2.dp, ring, shape),
        )
    }
}

private fun portColor(port: Port, isSnapTarget: Boolean): Color =
    if (isSnapTarget) EditorColors.portSnap else when (port.kind) {
        PortKind.EXECUTION -> EditorColors.execPort
        PortKind.DATA -> portTypeColor(port.schema)
    }

/**
 * A round handle carries one value, a square one carries a list.
 *
 * Unreal Blueprints' array-pin convention, and the reason [portTypeColor] gives a
 * list its *element's* color: the two questions a port answers — of what, and how
 * many — get one channel each, so "a list of dates" stays recognisably dates. The
 * size is unchanged either way, because the edge geometry anchors on
 * [GraphGeometry.PORT_RADIUS].
 */
private fun portShape(port: Port): Shape =
    if (portIsList(port.schema)) RoundedCornerShape(2.dp) else CircleShape
