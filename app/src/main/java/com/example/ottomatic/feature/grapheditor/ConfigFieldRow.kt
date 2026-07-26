package com.example.ottomatic.feature.grapheditor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.registry.ConfigField

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
    field: ConfigField<*>,
    port: Port?,
    wired: Boolean,
    sourceLabel: String?,
    value: String,
    reserveToggleGutter: Boolean,
    onValueChange: (String) -> Unit,
    onWiredChange: (Boolean) -> Unit,
) {
    if (port == null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = if (reserveToggleGutter) ConfigFieldToggleGutter else 0.dp),
        ) {
            ConfigFieldEditor(field = field, value = value, onValueChange = onValueChange)
        }
        return
    }
    val portColor = portTypeColor(port.schema)
    val connected = wired && sourceLabel != null
    Row(
        modifier = Modifier.fillMaxWidth(),
        // Top, not center: on a multiline field the socket belongs beside the
        // field's first line, not floating halfway down it.
        verticalAlignment = Alignment.Top,
    ) {
        // ConfigFieldEditor takes no Modifier, so the weight needs a host.
        Box(modifier = Modifier.weight(1f)) {
            ConfigFieldEditor(
                field = field,
                value = value,
                onValueChange = onValueChange,
                label = if (connected) "${field.label} ← $sourceLabel" else field.label,
                tint = if (connected) wiredFieldTint(portColor) else null,
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        DataInputSocket(
            label = field.label,
            wired = wired,
            portColor = portColor,
            sourceLabel = sourceLabel,
            onWiredChange = onWiredChange,
        )
    }
}

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
                    connected -> "Use the typed value for $label (connected to $sourceLabel)"
                    wired -> "Use the typed value for $label (nothing connected)"
                    else -> "Feed $label from upstream data"
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
