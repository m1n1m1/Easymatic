package com.example.ottomatic.feature.grapheditor

import com.example.ottomatic.core.model.ConfigKey
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ottomatic.R
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigOption
import com.example.ottomatic.feature.i18n.rememberNodeText
import androidx.compose.ui.res.stringResource

/**
 * Width the socket button occupies. Every field in a sheet that has any wirable
 * field reserves it, so the fields keep a common right edge instead of the
 * wirable ones being visibly short.
 */
internal val ConfigFieldToggleGutter = 44.dp

/** The button's touch target, and the socket drawn inside it. */
private val SocketButtonSize = 40.dp
private val SocketCircleSize = 32.dp

/**
 * How far down a field's first text row sits. Not half of the 56.dp box: a
 * labelled [androidx.compose.material3.OutlinedTextField] places its input text
 * *below* the floating label rather than centred, and the label's own overhang
 * counts too. Anchoring the socket here rather than to the box's middle is what
 * keeps it level on a multiline field.
 */
private val FieldFirstRowCenter = 35.dp

/**
 * Gap between the two gutter buttons when a field has both.
 *
 * Zero would work — the touch targets already carry their own padding — but a socket and an
 * ⓘ read as one control at that distance, and they do unrelated things.
 */
private val GutterButtonGap = 2.dp

/**
 * One config field in the node config sheet, plus — when the field is wirable —
 * the socket that switches it between "typed here" and "fed by an upstream node".
 *
 * A wirable field is one whose `@Wired` property also produced a DATA input port
 * (see [com.example.ottomatic.domain.registry.NodeSchema]); [port] is that port,
 * or `null` for a field that can only ever be typed. The socket sits next to the
 * field it governs rather than in a list at the bottom of the sheet, because the
 * two are the same setting seen from two sides.
 *
 * Wiring state is shown the way the canvas shows it, with no explanatory prose —
 * see [DataInputSocket]. A connected field additionally lights its outline and
 * label in the port's type color while muting its own text, because arriving data
 * outranks the typed value (`NodeSchema.decode` resolves data → typed value →
 * default). The field stays editable throughout: with nothing connected, or with
 * an edge that delivers nothing, the typed value is still what runs.
 *
 * [sourceLabel] names the far end of that edge, or is `null` when there is none.
 * [reserveToggleGutter] keeps an un-wirable field the same width as its wirable
 * neighbours; see [ConfigFieldToggleGutter].
 */
@Composable
internal fun ConfigFieldRow(
    typeId: NodeTypeId,
    field: ConfigField<*>,
    port: Port?,
    wired: Boolean,
    sourceLabel: String?,
    value: String,
    reserveToggleGutter: Boolean,
    onValueChange: (String) -> Unit,
    onWiredChange: (Boolean) -> Unit,
    siblingValue: (ConfigKey) -> String = { "" },
) {
    // Resolved here rather than inside the editor, which stays generic over any field:
    // the key needs the owning node, and this is the closest place that knows it.
    val nodeText = rememberNodeText()
    val fieldLabel = nodeText.fieldLabel(typeId, field)
    val hint = nodeText.fieldHint(typeId, field)
    val optionLabel: (ConfigOption) -> String = { nodeText.optionLabel(typeId, field.key, it) }
    val portColor = port?.let { portTypeColor(it.schema) }
    val connected = port != null && wired && sourceLabel != null
    var hintShown by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            // Top, not center: on a multiline field the socket belongs beside the
            // field's first line, not floating halfway down it.
            verticalAlignment = Alignment.Top,
        ) {
            // ConfigFieldEditor takes no Modifier, so the weight needs a host. A field with an
            // empty gutter still reserves it when a sibling has one, so the sheet keeps a
            // single right edge.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = if (gutterIsEmpty(port, hint, reserveToggleGutter)) {
                        ConfigFieldToggleGutter
                    } else {
                        0.dp
                    }),
            ) {
                ConfigFieldEditor(
                    field = field,
                    value = value,
                    onValueChange = onValueChange,
                    label = fieldLabel,
                    optionLabel = optionLabel,
                    tint = if (connected) wiredFieldTint(portColor ?: Color.Unspecified) else null,
                    siblingValue = siblingValue,
                )
            }
            FieldGutter(
                label = fieldLabel,
                port = port,
                portColor = portColor,
                wired = wired,
                sourceLabel = sourceLabel,
                hasHint = hint.isNotBlank(),
                hintShown = hintShown,
                onWiredChange = onWiredChange,
                onToggleHint = { hintShown = !hintShown },
            )
        }
        // Both lines sit outside the outline, which is the whole point: a notch is a gap in a
        // border and cannot wrap, so anything longer than the field's own name is unreadable
        // in it however much room the sheet has.
        if (connected) {
            SubLine(
                text = stringResource(R.string.config_field_wired_from, sourceLabel.orEmpty()),
                color = portColor ?: EditorColors.textSecondary,
            )
        }
        AnimatedVisibility(visible = hintShown) {
            SubLine(text = hint, color = EditorColors.textSecondary)
        }
    }
}

/**
 * The column of buttons to the right of a field: its data socket, its ⓘ, or both.
 *
 * Stacked rather than set side by side. The gutter is one button wide, and widening it would
 * take room from the field on *every* row in the sheet — the common right edge is the point of
 * reserving it — to serve the minority of fields that carry both.
 */
@Composable
private fun FieldGutter(
    label: String,
    port: Port?,
    portColor: Color?,
    wired: Boolean,
    sourceLabel: String?,
    hasHint: Boolean,
    hintShown: Boolean,
    onWiredChange: (Boolean) -> Unit,
    onToggleHint: () -> Unit,
) {
    if (port == null && !hasHint) return
    Spacer(modifier = Modifier.width(4.dp))
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (port != null && portColor != null) {
            DataInputSocket(
                label = label,
                wired = wired,
                portColor = portColor,
                sourceLabel = sourceLabel,
                onWiredChange = onWiredChange,
            )
        }
        if (hasHint) {
            if (port != null) Spacer(modifier = Modifier.height(GutterButtonGap))
            HintButton(
                label = label,
                shown = hintShown,
                anchored = port == null,
                onToggle = onToggleHint,
            )
        }
    }
}

/**
 * Whether this field contributes nothing to the gutter but must still leave room for it.
 *
 * [reserveToggleGutter] is the sheet's answer, not the field's: one field with a socket or an
 * ⓘ puts every field on the narrower measure, so the outlines keep a common right edge
 * instead of the plain ones running visibly wider.
 */
private fun gutterIsEmpty(port: Port?, hint: String, reserveToggleGutter: Boolean): Boolean =
    port == null && hint.isBlank() && reserveToggleGutter

/** A line under the outline, indented to the field's text. */
@Composable
private fun SubLine(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = ConfigFieldToggleGutter),
    )
}

/**
 * Reveals the sentence explaining a field.
 *
 * A button rather than a line that is always there, so a sheet of twenty fields does not become
 * mostly explanation for the sake of the one being filled in — and a button rather than a
 * tooltip, because a tooltip cannot be reopened by somebody who dismissed it by looking away.
 *
 * [anchored] levels it with the field's first text row when it is alone in the gutter. With a
 * socket above it the stack already sets its position, and anchoring twice would push it past
 * the bottom of a single-line field.
 */
@Composable
private fun HintButton(
    label: String,
    shown: Boolean,
    anchored: Boolean,
    onToggle: () -> Unit,
) {
    IconButton(
        onClick = onToggle,
        modifier = Modifier
            .padding(top = if (anchored) FieldFirstRowCenter - SocketButtonSize / 2 else 0.dp)
            .size(SocketButtonSize),
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = stringResource(
                if (shown) R.string.config_hint_hide else R.string.config_hint_show,
                label,
            ),
            tint = if (shown) EditorColors.textPrimary else EditorColors.textSecondary,
            modifier = Modifier.size(HintIconSize),
        )
    }
}

/** Smaller than the socket's ring, which is a port and has to read as one. */
private val HintIconSize = 20.dp

/**
 * The field's data input, as both the control and the picture of its own state —
 * a port handle you can press. Off, it is a bare broken-link glyph. Switched on it
 * becomes a **socket**, drawn as the node cards' handles are (`PortHandle` in
 * [com.example.ottomatic.feature.grapheditor.NodeCard]): a ring in the port's type
 * color around the canvas color. That socket is hollow while nothing is connected
 * and **fills** once an edge lands in it, at which point the link glyph inverts to
 * the canvas color so it stays legible — punched out of the fill.
 *
 * Switching it on is also what makes the node's DATA input handle appear on the
 * canvas, so there is something to drop that edge on (see [visibleInputPorts]).
 */
@Composable
private fun DataInputSocket(
    label: String,
    wired: Boolean,
    portColor: Color,
    sourceLabel: String?,
    onWiredChange: (Boolean) -> Unit,
) {
    val connected = wired && sourceLabel != null
    IconToggleButton(
        checked = wired,
        onCheckedChange = onWiredChange,
        modifier = Modifier
            .padding(top = FieldFirstRowCenter - SocketButtonSize / 2)
            .size(SocketButtonSize),
    ) {
        Box(
            modifier = Modifier
                .size(SocketCircleSize)
                .clip(CircleShape)
                .background(
                    when {
                        connected -> portColor
                        wired -> EditorColors.canvasBackground
                        else -> Color.Transparent
                    },
                )
                .then(
                    if (wired) Modifier.border(2.dp, portColor, CircleShape) else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (wired) Icons.Filled.Link else Icons.Filled.LinkOff,
                contentDescription = when {
                    connected -> stringResource(
                        R.string.config_socket_use_typed_connected,
                        label,
                        sourceLabel.orEmpty(),
                    )
                    wired -> stringResource(R.string.config_socket_use_typed_free, label)
                    else -> stringResource(R.string.config_socket_feed, label)
                },
                tint = when {
                    connected -> EditorColors.canvasBackground
                    wired -> portColor
                    else -> EditorColors.textSecondary
                },
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
