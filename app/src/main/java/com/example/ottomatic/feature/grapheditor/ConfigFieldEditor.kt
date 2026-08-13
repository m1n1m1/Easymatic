// One composable per widget a config field can be, plus one per PickerKind. The
// count tracks the number of field *types*, which is the file's whole subject.
@file:Suppress("TooManyFunctions")

package com.example.ottomatic.feature.grapheditor

import androidx.compose.ui.res.stringResource
import com.example.ottomatic.R
import com.example.ottomatic.core.model.ConfigKey
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.runtime.collectAsState
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
import com.example.ottomatic.core.service.SmartHomeTargetKind
import com.example.ottomatic.domain.model.NfcTagId
import com.example.ottomatic.domain.model.PortSpec
import com.example.ottomatic.domain.model.SmartHomeRef
import com.example.ottomatic.domain.model.TimeOfDay
import com.example.ottomatic.domain.model.WorkflowSummary
import com.example.ottomatic.domain.model.config.PickerKind
import com.example.ottomatic.domain.registry.ConfigOption
import com.example.ottomatic.feature.i18n.rememberNodeText
import com.example.ottomatic.domain.model.config.ValueType
import com.example.ottomatic.domain.model.schema.DateTime
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.domain.registry.enumConfigOptions
import com.example.ottomatic.feature.api.ApiTokenField
import com.example.ottomatic.feature.apps.AppPickerField
import com.example.ottomatic.feature.contacts.ContactNameField
import com.example.ottomatic.feature.contacts.PhoneNumberField
import com.example.ottomatic.feature.geofence.GeofencePlacePickerOverlay
import com.example.ottomatic.feature.geofence.LocalGeofencePlaces
import com.example.ottomatic.feature.ai.AiConnectionPickerOverlay
import com.example.ottomatic.feature.ai.LocalAiConnections
import com.example.ottomatic.feature.mail.LocalMailAccounts
import com.example.ottomatic.feature.mail.MailFolderField
import com.example.ottomatic.feature.mail.MailAccountPickerOverlay
import com.example.ottomatic.feature.nfc.LocalNfcTags
import com.example.ottomatic.feature.nfc.NfcTagPickerOverlay
import com.example.ottomatic.feature.smarthome.LightTargetPickerOverlay
import com.example.ottomatic.feature.smarthome.LocalSmartHome
import com.example.ottomatic.feature.sound.SoundPickerField
import com.example.ottomatic.feature.variables.LocalVariables
import com.example.ottomatic.feature.variables.VariablePickerOverlay
import com.example.ottomatic.feature.variables.VariableScope
import com.example.ottomatic.feature.variables.resolve
import com.example.ottomatic.feature.wifi.WifiNetworkField
import com.example.ottomatic.feature.workflowlist.LocalMacros
import com.example.ottomatic.feature.workflowlist.MacroPickerOverlay
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
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
    /**
     * How a dropdown choice is named. Supplied by [ConfigFieldRow], which knows the
     * owning node and can therefore build the key; this stays generic over any field.
     */
    optionLabel: (ConfigOption) -> String = { it.label },
    tint: ConfigFieldTint? = null,
    siblingValue: (ConfigKey) -> String = { "" },
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
                        value = selected?.let(optionLabel) ?: value,
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
                                text = { Text(optionLabel(option)) },
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
            ConfigFieldType.TIME_OF_DAY -> {
                TimeOfDayField(
                    value = value,
                    onValueChange = onValueChange,
                    labelSlot = labelSlot,
                    colors = colors,
                )
            }
            ConfigFieldType.PHONE -> {
                PhoneNumberField(
                    value = value,
                    onValueChange = onValueChange,
                    labelSlot = labelSlot,
                    colors = colors,
                )
            }
            is ConfigFieldType.MAIL_FOLDER -> {
                // The only field whose editor depends on another field's value:
                // folders live on a server, and which server is the account named
                // beside it. Hence [siblingValue] — everything else here is
                // answerable from the field alone.
                MailFolderField(
                    value = value,
                    accountId = siblingValue(ConfigKey(type.accountKey)),
                    onValueChange = onValueChange,
                    labelSlot = labelSlot,
                    colors = colors,
                )
            }
            ConfigFieldType.CONTACT_NAME -> {
                ContactNameField(
                    value = value,
                    onValueChange = onValueChange,
                    labelSlot = labelSlot,
                    colors = colors,
                )
            }
            ConfigFieldType.WIFI_NETWORK -> {
                WifiNetworkField(
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
            ConfigFieldType.API_TOKEN -> {
                ApiTokenField(
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
        placeholder = {
            Text(
                text = stringResource(R.string.config_now),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingIcon = {
            IconButton(onClick = { stage = DateTimeStage.DATE }) {
                Icon(imageVector = Icons.Filled.DateRange, contentDescription =
                    stringResource(R.string.grapheditor_pick_a_date_and_time))
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
                    ) { Text(stringResource(R.string.grapheditor_next)) }
                },
                dismissButton = {
                    TextButton(onClick = { stage = DateTimeStage.CLOSED }) { Text(
                        stringResource(R.string.grapheditor_cancel)) }
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
                    ) { Text(stringResource(R.string.config_set)) }
                },
                dismissButton = {
                    TextButton(onClick = { stage = DateTimeStage.CLOSED }) { Text(
                        stringResource(R.string.grapheditor_cancel)) }
                },
            )
        }
    }
}

/**
 * A `@TimeOfDay` field: a text field the user can type `HH:mm` into, with a clock
 * face that fills it in from a time picker.
 *
 * A clock rather than [DateTimeField]'s calendar, because a time of day is not an
 * instant — "only between 22:00 and 07:00" is true every night and belongs to no
 * date. Editable for the same reason that one is, plus one this one has alone: the
 * field has to be **clearable**, since a blank schedule window is how the trigger
 * says it is unbounded, and a read-only picker can never give a value back.
 *
 * The dial is seeded from whatever the field currently holds, re-read on every open,
 * so typing moves the dial too. [TimeOfDay.toString] is what writes the canonical
 * zero-padded form, which is also what the trigger parses — so the two agree by
 * construction rather than by both being careful.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeOfDayField(
    value: String,
    onValueChange: (String) -> Unit,
    labelSlot: @Composable () -> Unit,
    colors: TextFieldColors,
) {
    var picking by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = labelSlot,
        colors = colors,
        placeholder = { Text(text =
            stringResource(R.string.grapheditor_hh_mm), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = {
            IconButton(onClick = { picking = true }) {
                Icon(
                    imageVector = Icons.Filled.Schedule,
                    contentDescription = stringResource(R.string.grapheditor_pick_a_time),
                )
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    if (picking) {
        // Whatever is in the field, or the current time when it is empty or
        // unreadable — the same rule DateTimeField's seed follows.
        val seed = TimeOfDay.parse(value)
            ?: LocalTime.now().let { TimeOfDay.of(it.hour, it.minute) }
        val state = rememberTimePickerState(
            initialHour = seed.hour,
            initialMinute = seed.minute,
            // Fixed 24-hour, matching DateTimeField: a 12-hour dial over a field
            // that shows and stores HH:mm would have the two disagree about the
            // same value, which costs more than the locale nicety buys.
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { picking = false },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onValueChange(TimeOfDay.of(state.hour, state.minute).toString())
                        picking = false
                    },
                ) { Text(stringResource(R.string.config_set)) }
            },
            dismissButton = {
                TextButton(onClick = { picking = false }) { Text(stringResource(R.string.grapheditor_cancel)) }
            },
        )
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
                Text(stringResource(R.string.config_add_row, label.lowercase().removeSuffix("s")))
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
/**
 * The wildcard row in a `@Ports` type dropdown.
 *
 * A plain constant because `PORT_TYPE_OPTIONS` is a top-level val with no composition
 * to read from; `NodeText.valueTypeLabel` translates it at the point it is drawn.
 */
private const val ANYTHING_LABEL = "Anything"

private val PORT_TYPE_OPTIONS: List<ConfigOption> =
    listOf(ConfigOption(value = PortSpec.ANY, label = ANYTHING_LABEL)) +
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
            label = {
                Text(
                    text = stringResource(R.string.grapheditor_name),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
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
                value = options.firstOrNull { it.value == selected }
                    ?.let { rememberNodeText().valueTypeLabel(it) }
                    .orEmpty(),
                onValueChange = {},
                readOnly = true,
                label = {
                    Text(
                        text = stringResource(R.string.grapheditor_type),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
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
                        text = { Text(rememberNodeText().valueTypeLabel(option)) },
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
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.grapheditor_remove_port))
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
            contentDescription = stringResource(
                if (on) R.string.config_port_carries_list else R.string.config_port_carries_one,
            ),
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
        // The two app kinds share one field and one overlay; all they disagree
        // about is which apps are worth offering and whether "any" is an answer.
        PickerKind.APP ->
            AppPickerField(value, onValueChange, launchableOnly = true, allowAny = false, labelSlot, colors)
        PickerKind.APP_FILTER ->
            AppPickerField(value, onValueChange, launchableOnly = false, allowAny = true, labelSlot, colors)
        PickerKind.MACRO -> MacroPickerField(value, onValueChange, labelSlot, colors)
        PickerKind.NFC_TAG -> NfcTagPickerField(value, onValueChange, labelSlot, colors)
        PickerKind.MAIL_ACCOUNT -> MailAccountPickerField(value, onValueChange, labelSlot, colors)
        PickerKind.AI_CONNECTION -> AiConnectionPickerField(value, onValueChange, labelSlot, colors)
        // The two light kinds share one field and one overlay, on the app kinds'
        // precedent: same hub, same snapshot, different section of it.
        PickerKind.LIGHT_TARGET ->
            SmartHomePickerField(value, onValueChange, SmartHomeTargetKind.LIGHT, labelSlot, colors)
        PickerKind.LIGHT_SCENE ->
            SmartHomePickerField(value, onValueChange, SmartHomeTargetKind.SCENE, labelSlot, colors)
    }
}

/**
 * A `@Picker(LIGHT_TARGET)` or `@Picker(LIGHT_SCENE)` field: which light, room, zone
 * or scene this node acts on.
 *
 * It is the one picker whose field is **usable with no library in scope**, and that
 * is not an oversight in the others. What is stored here is a whole
 * [SmartHomeRef] spec carrying the name it had when it was chosen, so this field can
 * render "Kitchen ceiling" with no ViewModel provided, nothing cached on disk and
 * the bridge unplugged — which no id-based picker can manage. The overlay still
 * needs the library, as everywhere else; only the *reading* is free.
 *
 * A spec that parses but names a hub which is gone reads as "… · hub removed"
 * rather than falling back to a raw id: the name is right there and still the most
 * useful thing to show, and what is broken is the hub, not the reference.
 */
@Composable
private fun SmartHomePickerField(
    value: String,
    onValueChange: (String) -> Unit,
    kind: SmartHomeTargetKind,
    labelSlot: @Composable () -> Unit,
    colors: TextFieldColors,
) {
    var picking by remember { mutableStateOf(false) }
    val hubs = LocalSmartHome.current
    val parsed = SmartHomeRef.parse(value)

    PickerFieldChrome(
        display = when {
            value.isBlank() -> ""
            parsed == null -> value
            hubs != null && hubs.hubById(parsed.hubId) == null ->
                stringResource(R.string.config_hub_removed, parsed.name)
            else -> parsed.name
        },
        icon = if (kind == SmartHomeTargetKind.SCENE) Icons.Filled.AutoAwesome else Icons.Filled.Lightbulb,
        enabled = hubs != null,
        onTap = { picking = true },
        labelSlot = labelSlot,
        colors = colors,
    )

    if (picking && hubs != null) {
        LightTargetPickerOverlay(
            viewModel = hubs,
            kind = kind,
            selected = value,
            onPick = { spec ->
                onValueChange(spec)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

/**
 * A `@Picker(MAIL_ACCOUNT)` field: which account this node sends or reads through.
 *
 * The mirror image of [NfcTagPickerField] on both of its peculiarities. Blank is
 * **not** an answer — there is no account to fall back on — so the placeholder is
 * the ordinary "None selected". And an id that resolves to nothing is genuinely
 * broken rather than merely unnamed, since the account carried the host, the
 * username and the password: it falls back to a phrase saying so instead of to a
 * prettified id, because there is nothing about a deleted account left to format.
 */
@Composable
private fun MailAccountPickerField(
    value: String,
    onValueChange: (String) -> Unit,
    labelSlot: @Composable () -> Unit,
    colors: TextFieldColors,
) {
    var picking by remember { mutableStateOf(false) }
    val accounts = LocalMailAccounts.current

    PickerFieldChrome(
        display = when {
            value.isBlank() -> ""
            else -> accounts?.accountById(value)?.name ?: stringResource(R.string.config_deleted_account)
        },
        icon = Icons.Filled.Mail,
        enabled = accounts != null,
        onTap = { picking = true },
        labelSlot = labelSlot,
        colors = colors,
    )

    if (picking && accounts != null) {
        MailAccountPickerOverlay(
            viewModel = accounts,
            selectedId = value.takeIf { it.isNotBlank() },
            onPick = { id ->
                onValueChange(id)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

/**
 * A `@Picker(AI_CONNECTION)` field: which connection this node sends prompts
 * through.
 *
 * [MailAccountPickerField]'s twin on both of its peculiarities, and for the same
 * reasons. Blank is **not** an answer — there is no connection to fall back on, and
 * an implicit one would silently bill a key the user did not choose — so the
 * placeholder is the ordinary "None selected". And an id that resolves to nothing
 * is genuinely broken rather than merely unnamed, since the connection carried the
 * provider and the key: it falls back to a phrase saying so instead of to a
 * prettified id, because there is nothing about a deleted connection left to
 * format.
 */
@Composable
private fun AiConnectionPickerField(
    value: String,
    onValueChange: (String) -> Unit,
    labelSlot: @Composable () -> Unit,
    colors: TextFieldColors,
) {
    var picking by remember { mutableStateOf(false) }
    val connections = LocalAiConnections.current

    PickerFieldChrome(
        display = when {
            value.isBlank() -> ""
            else -> connections?.connectionById(value)?.name ?: stringResource(R.string.config_deleted_connection)
        },
        icon = Icons.Filled.Psychology,
        enabled = connections != null,
        onTap = { picking = true },
        labelSlot = labelSlot,
        colors = colors,
    )

    if (picking && connections != null) {
        AiConnectionPickerOverlay(
            viewModel = connections,
            selectedId = value.takeIf { it.isNotBlank() },
            onPick = { id ->
                onValueChange(id)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

/**
 * A `@Picker(NFC_TAG)` field: the tag this trigger watches, or any tag.
 *
 * Two things set it apart from the pickers above. Blank is a **real answer** rather
 * than an unset field, so the placeholder says "Any tag" instead of "None selected".
 * And an id that resolves to no saved tag falls back to the id *formatted for
 * reading* rather than the raw string: unlike a dangling place or macro id, this one
 * still works — the library only ever supplied the name — so it should look like a
 * tag whose name has been forgotten, not like something broken.
 */
@Composable
private fun NfcTagPickerField(
    value: String,
    onValueChange: (String) -> Unit,
    labelSlot: @Composable () -> Unit,
    colors: TextFieldColors,
) {
    var picking by remember { mutableStateOf(false) }
    val tags = LocalNfcTags.current

    PickerFieldChrome(
        display = when {
            value.isBlank() -> ""
            else -> tags?.tagByUid(value)?.name ?: NfcTagId.display(value)
        },
        icon = Icons.Filled.Nfc,
        enabled = tags != null,
        onTap = { picking = true },
        labelSlot = labelSlot,
        colors = colors,
        placeholder = stringResource(R.string.grapheditor_any_tag),
    )

    if (picking && tags != null) {
        NfcTagPickerOverlay(
            viewModel = tags,
            selectedUid = value.takeIf { it.isNotBlank() },
            onPick = { uid ->
                onValueChange(uid)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

/**
 * A `@Picker(MACRO)` field: the name of the macro this node acts on.
 *
 * Follows [VariablePickerField]'s three-way display rule exactly, and for the same
 * reason: a blank field is unconfigured, a resolvable id is a name, and an id that
 * resolves to nothing shows as **itself** rather than as an empty box — an empty
 * box reads as "never set" when the truth is "pointing at something deleted", and
 * only the Problems panel can say which. See `validateMacroRefs`.
 */
@Composable
private fun MacroPickerField(
    value: String,
    onValueChange: (String) -> Unit,
    labelSlot: @Composable () -> Unit,
    colors: TextFieldColors,
) {
    var picking by remember { mutableStateOf(false) }
    val library = LocalMacros.current
    // Unconditional `remember`, even though the fallback is only used with no
    // library in scope: a `remember` behind an elvis is a conditional call, and its
    // slot would shift the moment the local went from present to absent.
    val empty = remember { MutableStateFlow(emptyList<WorkflowSummary>()) }
    val macros by (library?.macros ?: empty).collectAsState()

    PickerFieldChrome(
        display = when {
            value.isBlank() -> ""
            else -> macros.firstOrNull { it.id == value }?.name ?: value
        },
        icon = Icons.Filled.AccountTree,
        enabled = library != null,
        onTap = { picking = true },
        labelSlot = labelSlot,
        colors = colors,
    )

    if (picking && library != null) {
        MacroPickerOverlay(
            macros = macros,
            selectedId = value.takeIf { it.isNotBlank() },
            editingId = library.editingId,
            onPick = { id ->
                onValueChange(id)
                picking = false
            },
            onDismiss = { picking = false },
        )
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
            resolved.first == VariableScope.GLOBAL ->
                stringResource(R.string.config_global_variable, resolved.second.name)
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

/**
 * The read-only field every picker wears, minus whatever opens on a tap.
 *
 * [placeholder] is what an empty field says, and it is a parameter because blank
 * does not mean the same thing everywhere: for most pickers it means the node is
 * unconfigured, but for a tag filter it is the answer "any tag". A field that says
 * "None selected" about a deliberate choice reads as a job left half-done.
 */
@Composable
internal fun PickerFieldChrome(
    display: String,
    icon: ImageVector,
    enabled: Boolean,
    onTap: () -> Unit,
    labelSlot: @Composable () -> Unit,
    colors: TextFieldColors,
    placeholder: String? = null,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            label = labelSlot,
            colors = colors,
            placeholder = {
                Text(
                    text = placeholder ?: stringResource(R.string.config_none_selected),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
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
