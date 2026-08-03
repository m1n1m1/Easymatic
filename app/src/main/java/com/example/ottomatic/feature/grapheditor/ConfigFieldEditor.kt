// One composable per widget a config field can be, plus one per PickerKind. The
// count tracks the number of field *types*, which is the file's whole subject.
@file:Suppress("TooManyFunctions")

package com.example.ottomatic.feature.grapheditor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ottomatic.domain.model.PortSpec
import com.example.ottomatic.domain.model.config.PickerKind
import com.example.ottomatic.domain.model.config.ValueType
import com.example.ottomatic.domain.model.schema.DateTime
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.domain.registry.ConfigOption
import com.example.ottomatic.domain.registry.enumConfigOptions
import com.example.ottomatic.feature.geofence.GeofencePlacePickerOverlay
import com.example.ottomatic.feature.geofence.LocalGeofencePlaces
import com.example.ottomatic.feature.sound.SoundPickerField
import com.example.ottomatic.feature.variables.LocalVariables
import com.example.ottomatic.feature.variables.VariablePickerOverlay
import com.example.ottomatic.feature.variables.VariableScope
import com.example.ottomatic.feature.variables.resolve
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

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
            ConfigFieldType.DATE_TIME -> {
                DateTimeField(
                    value = value,
                    onValueChange = onValueChange,
                    labelSlot = labelSlot,
                    colors = colors,
                )
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
            ConfigFieldType.PORT_LIST -> {
                PortListField(
                    value = value,
                    onValueChange = onValueChange,
                    label = label,
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

/** Which half of the date/time chooser is on screen. */
private enum class DateTimeStage { CLOSED, DATE, TIME }

/**
 * A date/time field: a text field the user can type into, with a calendar button
 * that fills it in from a date picker followed by a time picker.
 *
 * Unlike a `@Picker` this stays **editable**. The stored text is read through
 * [DateTime.parse], which accepts more than a calendar can express — most
 * importantly a bare `18:00`, meaning "today at 18:00", which is how a comparison
 * says "after six" and still means it tomorrow. The picker writes the canonical
 * ISO-8601 form; anything the parser rejects simply leaves the property on its
 * default when the node runs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeField(
    value: String,
    onValueChange: (String) -> Unit,
    labelSlot: @Composable () -> Unit,
    colors: TextFieldColors,
) {
    var stage by remember { mutableStateOf(DateTimeStage.CLOSED) }
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }
    // What the pickers open on: whatever is in the field, or now if it is empty
    // or unreadable. Read at open time, so editing the text moves the picker too.
    val seed = ZonedDateTime.ofInstant(
        Instant.ofEpochMilli((DateTime.parse(value) ?: DateTime.now()).epochMs),
        ZoneId.systemDefault(),
    )

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = labelSlot,
        colors = colors,
        placeholder = { Text(text = "Now", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = {
            IconButton(onClick = { stage = DateTimeStage.DATE }) {
                Icon(imageVector = Icons.Filled.DateRange, contentDescription = "Pick a date and time")
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    when (stage) {
        DateTimeStage.CLOSED -> Unit
        DateTimeStage.DATE -> {
            // The date picker works in UTC, so the seed is that *calendar day* at
            // UTC midnight rather than the instant itself — otherwise a late
            // evening east of Greenwich opens on the wrong day.
            val dateState = rememberDatePickerState(
                initialSelectedDateMillis = seed.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            )
            DatePickerDialog(
                onDismissRequest = { stage = DateTimeStage.CLOSED },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pickedDate = dateState.selectedDateMillis?.let {
                                Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                            }
                            stage = DateTimeStage.TIME
                        },
                    ) { Text("Next") }
                },
                dismissButton = {
                    TextButton(onClick = { stage = DateTimeStage.CLOSED }) { Text("Cancel") }
                },
            ) {
                DatePicker(state = dateState)
            }
        }
        DateTimeStage.TIME -> {
            val timeState = rememberTimePickerState(
                initialHour = seed.hour,
                initialMinute = seed.minute,
                is24Hour = true,
            )
            AlertDialog(
                onDismissRequest = { stage = DateTimeStage.CLOSED },
                text = { TimePicker(state = timeState) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val day = pickedDate ?: seed.toLocalDate()
                            val at = day.atTime(timeState.hour, timeState.minute).atZone(ZoneId.systemDefault())
                            onValueChange(DateTime(at.toInstant().toEpochMilli()).toString())
                            stage = DateTimeStage.CLOSED
                        },
                    ) { Text("Set") }
                },
                dismissButton = {
                    TextButton(onClick = { stage = DateTimeStage.CLOSED }) { Text("Cancel") }
                },
            )
        }
    }
}

/**
 * A `@Ports` field: the rows of data ports a script declares, each a name and a
 * type. Used for both its inputs and its outputs — they are one [PortSpec] seen
 * from opposite directions, so they share this editor.
 *
 * Fully controlled — there is no local editing state, so what the canvas shows
 * and what the form shows can never disagree. That means rendering rows
 * *leniently*: [PortSpec.parseLenient] keeps a row whose name is half-typed or
 * momentarily blank, where [PortSpec.parse] (which decides the actual ports)
 * drops it. A port therefore appears on the node the moment its name becomes
 * valid, and the row it came from never disappears from under the cursor.
 *
 * The row count is free to reach zero: a script that reads nothing is a real
 * thing, and the *outputs* side's floor is enforced by
 * [PortSpec.parseOutputs] where the ports are built, not by the form.
 */
@Composable
private fun PortListField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    colors: TextFieldColors,
) {
    val rows = remember(value) { value.lines().filter { it.isNotBlank() }.map(PortSpec::parseLenient) }
    val options = remember { PORT_TYPE_OPTIONS }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = label, color = EditorColors.textPrimary, fontSize = 14.sp)
        rows.forEachIndexed { index, row ->
            PortListRow(
                row = row,
                options = options,
                colors = colors,
                onNameChange = { onValueChange(PortSpec.encode(rows.replacing(index, row.copy(name = it)))) },
                onTypeChange = { onValueChange(PortSpec.encode(rows.replacing(index, row.copy(type = it)))) },
                onListChange = { onValueChange(PortSpec.encode(rows.replacing(index, row.copy(list = it)))) },
                onRemove = { onValueChange(PortSpec.encode(rows.dropping(index))) },
            )
        }
        if (rows.size < PortSpec.MAX_PORTS) {
            TextButton(
                onClick = { onValueChange(PortSpec.encode(rows + PortSpec("", null))) },
            ) {
                Text("Add ${label.lowercase().removeSuffix("s")}")
            }
        }
    }
}

/**
 * The type choices for one port row: every [ValueType], plus "Anything" for a
 * wildcard.
 *
 * Labelled through [enumConfigOptions] rather than spelled out, so "Date & time"
 * is written once. "Anything" leads because it is what an unconfigured input is
 * and the only choice that accepts a struct.
 */
private val PORT_TYPE_OPTIONS: List<ConfigOption> =
    listOf(ConfigOption(value = PortSpec.ANY, label = "Anything")) +
        enumConfigOptions(ValueType.serializer().descriptor)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortListRow(
    row: PortSpec,
    options: List<ConfigOption>,
    colors: TextFieldColors,
    onNameChange: (String) -> Unit,
    onTypeChange: (ValueType?) -> Unit,
    onListChange: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = row.name,
            onValueChange = onNameChange,
            label = { Text(text = "Name", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            colors = colors,
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(1f),
        ) {
            val selected = row.type?.name ?: PortSpec.ANY
            OutlinedTextField(
                value = options.firstOrNull { it.value == selected }?.label.orEmpty(),
                onValueChange = {},
                readOnly = true,
                label = { Text(text = "Type", maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            // "Anything" is not a ValueType, so a failed lookup
                            // is the wildcard rather than an error.
                            onTypeChange(runCatching { ValueType.valueOf(option.value) }.getOrNull())
                            expanded = false
                        },
                    )
                }
            }
        }
        ListToggle(on = row.list, onChange = onListChange)
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, contentDescription = "Remove port")
        }
    }
}

/**
 * "Is this port a list?", as a toggle beside the type rather than as more entries
 * in the type dropdown.
 *
 * Type and count are two independent questions — a list of text and a single text
 * differ in one of them — so folding them into one control would double the
 * dropdown and still have no way to say "a list of anything". Unreal Blueprints
 * splits the same pair across two controls on a pin for the same reason.
 */
@Composable
private fun ListToggle(on: Boolean, onChange: (Boolean) -> Unit) {
    IconToggleButton(checked = on, onCheckedChange = onChange) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.FormatListBulleted,
            contentDescription = if (on) "This port carries a list" else "This port carries one value",
            tint = if (on) EditorColors.textPrimary else EditorColors.textSecondary,
        )
    }
}

private fun List<PortSpec>.replacing(index: Int, spec: PortSpec): List<PortSpec> =
    mapIndexed { position, existing -> if (position == index) spec else existing }

private fun List<PortSpec>.dropping(index: Int): List<PortSpec> =
    filterIndexed { position, _ -> position != index }

/**
 * A `@Picker` field: shows the chosen thing's human name and opens its chooser
 * on tap. The stored value is an identifier, so the field is read-only — typing
 * a UUID by hand is not a use case worth supporting, and letting it be typed
 * would let it be typed *wrong*.
 *
 * Each kind brings its own chooser and its own way of naming what was chosen,
 * so the `when` dispatches once to a per-kind field rather than branching again
 * for the label, the icon and the overlay. It is exhaustive over [PickerKind]:
 * a new kind is a compile error until it is given a chooser here.
 */
@Composable
private fun PickerField(
    kind: PickerKind,
    value: String,
    onValueChange: (String) -> Unit,
    labelSlot: @Composable () -> Unit,
    colors: TextFieldColors,
) {
    when (kind) {
        PickerKind.GEOFENCE_PLACE -> GeofencePickerField(value, onValueChange, labelSlot, colors)
        PickerKind.SOUND -> SoundPickerField(value, onValueChange, labelSlot, colors)
        PickerKind.VARIABLE -> VariablePickerField(value, onValueChange, labelSlot, colors)
    }
}

@Composable
private fun VariablePickerField(
    value: String,
    onValueChange: (String) -> Unit,
    labelSlot: @Composable () -> Unit,
    colors: TextFieldColors,
) {
    var picking by remember { mutableStateOf(false) }
    val library = LocalVariables.current
    val resolved = library?.resolve(value)

    PickerFieldChrome(
        display = when {
            value.isBlank() -> ""
            // No library in scope (preview/test) or the declaration was deleted:
            // the raw ref, rather than an empty field that reads as unconfigured
            // when it is really dangling. The Problems panel says which it is.
            resolved == null -> value
            // The scope is shown because two variables may legitimately share a
            // name, and "which one did I pick?" is otherwise unanswerable here.
            resolved.first == VariableScope.GLOBAL -> "Global · ${resolved.second.name}"
            else -> resolved.second.name
        },
        icon = Icons.Filled.Tag,
        enabled = library != null,
        onTap = { picking = true },
        labelSlot = labelSlot,
        colors = colors,
    )

    if (picking && library != null) {
        VariablePickerOverlay(
            library = library,
            selectedSpec = value.takeIf { it.isNotBlank() },
            onPick = { spec ->
                onValueChange(spec)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun GeofencePickerField(
    value: String,
    onValueChange: (String) -> Unit,
    labelSlot: @Composable () -> Unit,
    colors: TextFieldColors,
) {
    var picking by remember { mutableStateOf(false) }
    val places = LocalGeofencePlaces.current

    PickerFieldChrome(
        display = when {
            value.isBlank() -> ""
            // No library in scope (preview/test) or the place was deleted: show
            // the raw id rather than an empty field that looks unconfigured.
            else -> places?.placeById(value)?.name ?: value
        },
        icon = Icons.Filled.Map,
        enabled = places != null,
        onTap = { picking = true },
        labelSlot = labelSlot,
        colors = colors,
    )

    if (picking && places != null) {
        GeofencePlacePickerOverlay(
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

/** The read-only field every picker wears, minus whatever opens on a tap. */
@Composable
internal fun PickerFieldChrome(
    display: String,
    icon: ImageVector,
    enabled: Boolean,
    onTap: () -> Unit,
    labelSlot: @Composable () -> Unit,
    colors: TextFieldColors,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            label = labelSlot,
            colors = colors,
            placeholder = { Text(text = "None selected", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { Icon(imageVector = icon, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // A read-only field still consumes its own taps, so the tap target is a
        // transparent layer over it rather than a clickable on the field.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(enabled = enabled) { onTap() },
        )
    }
}
