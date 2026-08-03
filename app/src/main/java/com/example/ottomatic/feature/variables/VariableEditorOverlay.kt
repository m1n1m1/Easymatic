package com.example.ottomatic.feature.variables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ottomatic.domain.model.VariableDeclaration
import com.example.ottomatic.domain.model.config.ValueType
import com.example.ottomatic.domain.registry.ConfigField
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.domain.registry.ConfigOption
import com.example.ottomatic.feature.grapheditor.ConfigFieldEditor
import com.example.ottomatic.feature.grapheditor.EditorColors
import com.example.ottomatic.feature.grapheditor.EditorOverlay
import com.example.ottomatic.feature.grapheditor.editorSwitchColors
import java.util.UUID

/**
 * Creates or edits one variable.
 *
 * A full-screen [EditorOverlay] rather than a form inside the dock, for three
 * reasons that all point the same way: the dock is about half the screen and the
 * keyboard would take the rest, an overlay is a `Dialog` with its own window so its
 * `imePadding` actually works and the back gesture dismisses it for free, and the
 * picker's "New variable" has to open *this* editor stacked above itself — exactly
 * as the geofence picker stacks the place editor.
 *
 * The draft is held here rather than in a ViewModel, unlike
 * [com.example.ottomatic.feature.geofence.GeofenceDraft]. That one needs a
 * ViewModel because a map editor has asynchronous work behind it — a location fix,
 * a geocode — that must survive a recomposition. This is four fields and a switch;
 * there is nothing to outlive.
 *
 * **Scope is chosen once, at creation.** A ref carries its scope, so moving a
 * variable between the two would mean rewriting every node that points at it —
 * across other workflows, for a global. Renaming, by contrast, is free, because a
 * ref carries an id and never a name.
 */
@Composable
@Suppress("LongParameterList") // A create and an edit of two scopes; each parameter distinguishes one.
fun VariableEditorOverlay(
    initial: VariableDeclaration?,
    scope: VariableScope,
    canChooseScope: Boolean,
    onSave: (VariableScope, VariableDeclaration) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(initial ?: blankDeclaration()) }
    var chosenScope by remember { mutableStateOf(scope) }
    var saved by remember { mutableStateOf(false) }

    EditorOverlay(
        title = if (initial == null) "New variable" else "Edit variable",
        onClose = { if (saved) onSave(chosenScope, draft.trimmed()) else onDismiss() },
        action = {
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete variable", tint = EditorColors.textSecondary)
                }
            }
            TextButton(
                onClick = { saved = true },
                enabled = draft.name.isNotBlank(),
            ) { Text("Save") }
        },
    ) { dismiss ->
        // The Save button sets a flag and dismisses; the write happens in onClose,
        // after the exit animation — the deferred-commit idiom every overlay here
        // uses, so a save never redraws the list underneath a sliding window.
        if (saved) dismiss()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (canChooseScope) {
                ScopeChooser(selected = chosenScope, onSelect = { chosenScope = it })
            }
            ConfigFieldEditor(
                field = ConfigField(NAME_KEY, "Name", ConfigFieldType.STR, ""),
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
            )
            ConfigFieldEditor(
                field = ConfigField(TYPE_KEY, "Holds", ConfigFieldType.ENUM(TYPE_OPTIONS), ValueType.TEXT.name),
                value = draft.type.name,
                onValueChange = { draft = draft.copy(type = ValueType.valueOf(it)) },
            )
            // The initial value's widget follows the chosen type, so a "Yes or no"
            // variable starts with a switch rather than the word "true" typed into
            // a text field. Same mapping `action.if`'s compare-against literal uses.
            ConfigFieldEditor(
                field = ConfigField(
                    VALUE_KEY,
                    if (draft.constant) "Value" else "Starts at",
                    literalTypeFor(draft.type),
                    "",
                ),
                value = draft.initialValue,
                onValueChange = { draft = draft.copy(initialValue = it) },
            )
            ConstantSwitch(
                constant = draft.constant,
                onChange = { draft = draft.copy(constant = it) },
            )
            ConfigFieldEditor(
                field = ConfigField(NOTE_KEY, "Note (optional)", ConfigFieldType.MULTILINE, ""),
                value = draft.description,
                onValueChange = { draft = draft.copy(description = it) },
            )
        }
    }
}

@Composable
private fun ScopeChooser(selected: VariableScope, onSelect: (VariableScope) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Belongs to",
            style = MaterialTheme.typography.labelMedium,
            color = EditorColors.textSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScopeChip("This workflow", selected == VariableScope.LOCAL) { onSelect(VariableScope.LOCAL) }
            ScopeChip("Every workflow", selected == VariableScope.GLOBAL) { onSelect(VariableScope.GLOBAL) }
        }
        Text(
            text = "Chosen once — a variable cannot move between the two later, because the nodes " +
                "pointing at it record which set it is in.",
            style = MaterialTheme.typography.bodySmall,
            color = EditorColors.textSecondary,
        )
    }
}

@Composable
private fun ScopeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = EditorColors.nodeBackground,
            labelColor = EditorColors.textSecondary,
            selectedContainerColor = EditorColors.triggerAccent.copy(alpha = 0.22f),
            selectedLabelColor = EditorColors.textPrimary,
        ),
    )
}

@Composable
private fun ConstantSwitch(constant: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Switch(checked = constant, onCheckedChange = onChange, colors = editorSwitchColors())
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Constant",
                style = MaterialTheme.typography.bodyLarge,
                color = EditorColors.textPrimary,
            )
            Text(
                text = "Its value is fixed here. A node that tries to write it says so and carries on.",
                style = MaterialTheme.typography.bodySmall,
                color = EditorColors.textSecondary,
            )
        }
    }
}

/**
 * The form widget an initial value of [type] should get.
 *
 * Mirrors what `compareSchema` does for `action.if`'s compare-against literal:
 * asking someone to type `true` into a text box when the value is a yes/no is the
 * kind of small wrongness that makes a form feel untrustworthy.
 */
private fun literalTypeFor(type: ValueType): ConfigFieldType<*> = when (type) {
    ValueType.TEXT -> ConfigFieldType.STR
    ValueType.NUMBER -> ConfigFieldType.DOUBLE
    ValueType.WHOLE_NUMBER -> ConfigFieldType.INT
    ValueType.YES_OR_NO -> ConfigFieldType.BOOL
    ValueType.DATE_TIME -> ConfigFieldType.DATE_TIME
}

private fun blankDeclaration() = VariableDeclaration(id = UUID.randomUUID().toString(), name = "")

private fun VariableDeclaration.trimmed() = copy(name = name.trim(), initialValue = initialValue.trim())

private val TYPE_OPTIONS: List<ConfigOption> =
    ValueType.entries.map { ConfigOption(value = it.name, label = it.label()) }

private val NAME_KEY = com.example.ottomatic.core.model.ConfigKey("name")
private val TYPE_KEY = com.example.ottomatic.core.model.ConfigKey("type")
private val VALUE_KEY = com.example.ottomatic.core.model.ConfigKey("initialValue")
private val NOTE_KEY = com.example.ottomatic.core.model.ConfigKey("description")
