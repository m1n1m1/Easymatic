package com.example.ottomatic.feature.grapheditor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ottomatic.domain.model.config.PickerKind
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.feature.geofence.GeofencePlacePickerOverlay
import com.example.ottomatic.feature.geofence.LocalGeofencePlaces

/**
 * The input widget for one config field, chosen by its [ConfigFieldType] — the
 * single place a config value is edited, reached through [ConfigFieldRow].
 *
 * [label] defaults to the field's own label but can be overridden to carry extra
 * information on the outline — [ConfigFieldRow] appends the wiring source to it.
 * [tint] recolors the widget when the field is fed by an edge; see
 * [ConfigFieldTint].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("CyclomaticComplexMethod", "LongMethod") // Inherent: one branch per ConfigFieldType.
@Composable
internal fun ConfigFieldEditor(
    field: ConfigField<*>,
    value: String,
    onValueChange: (String) -> Unit,
    label: String = field.label,
    tint: ConfigFieldTint? = null,
) {
    val type = field.type
    var expanded by remember { mutableStateOf(false) }
    val colors = tint.asTextFieldColors()
    // Single line + ellipsis: the label can carry the wiring source, and a long
    // node name must not wrap the field's outline open.
    val labelSlot: @Composable () -> Unit = {
        Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Column {
        when (type) {
            is ConfigFieldType.ENUM -> {
                val selected = type.options.firstOrNull { it.value == value }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                ) {
                    OutlinedTextField(
                        value = selected?.label ?: value,
                        onValueChange = {},
                        readOnly = true,
                        label = labelSlot,
                        colors = colors,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
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
            ConfigFieldType.MULTILINE -> {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = labelSlot,
                    colors = colors,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            }
            ConfigFieldType.INT -> {
                OutlinedTextField(
                    value = value,
                    onValueChange = { new ->
                        if (new.matches(Regex("-?\\d*")) || new.isEmpty()) onValueChange(new)
                    },
                    label = labelSlot,
                    colors = colors,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ConfigFieldType.DOUBLE -> {
                OutlinedTextField(
                    value = value,
                    onValueChange = { new ->
                        if (new.matches(Regex("-?\\d*\\.?\\d*")) || new.isEmpty()) onValueChange(new)
                    },
                    label = labelSlot,
                    colors = colors,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ConfigFieldType.BOOL -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Switch(
                        checked = value.toBooleanStrictOrNull() == true,
                        onCheckedChange = { onValueChange(it.toString()) },
                    )
                    // No outline to tint, so the label carries the wired coloring.
                    Text(
                        text = label,
                        color = tint?.label ?: EditorColors.textPrimary,
                        fontSize = 14.sp,
                    )
                }
            }
            is ConfigFieldType.PICKER -> {
                PickerField(
                    kind = type.kind,
                    value = value,
                    onValueChange = onValueChange,
                    labelSlot = labelSlot,
                    colors = colors,
                )
            }
            ConfigFieldType.STR -> {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = labelSlot,
                    colors = colors,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * A `@Picker` field: shows the chosen thing's human name and opens its chooser
 * on tap. The stored value is an identifier, so the field is read-only — typing
 * a UUID by hand is not a use case worth supporting, and letting it be typed
 * would let it be typed *wrong*.
 *
 * The `when` is exhaustive over [PickerKind]: a new kind is a compile error
 * until it is given a chooser here.
 */
@Composable
private fun PickerField(
    kind: PickerKind,
    value: String,
    onValueChange: (String) -> Unit,
    labelSlot: @Composable () -> Unit,
    colors: TextFieldColors,
) {
    var picking by remember { mutableStateOf(false) }
    val places = LocalGeofencePlaces.current

    val display = when (kind) {
        PickerKind.GEOFENCE_PLACE -> when {
            value.isBlank() -> ""
            // No library in scope (preview/test) or the place was deleted: show
            // the raw id rather than an empty field that looks unconfigured.
            else -> places?.placeById(value)?.name ?: value
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            label = labelSlot,
            colors = colors,
            placeholder = { Text(text = "None selected", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = {
                Icon(
                    imageVector = when (kind) {
                        PickerKind.GEOFENCE_PLACE -> Icons.Filled.Map
                    },
                    contentDescription = null,
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // A read-only field still consumes its own taps, so the tap target is a
        // transparent layer over it rather than a clickable on the field.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(enabled = places != null) { picking = true },
        )
    }

    if (picking && places != null) {
        when (kind) {
            PickerKind.GEOFENCE_PLACE -> GeofencePlacePickerOverlay(
                viewModel = places,
                selectedId = value.takeIf { it.isNotBlank() },
                onPick = { id ->
                    onValueChange(id)
                    picking = false
                },
                onDismiss = { picking = false },
            )
        }
    }
}
