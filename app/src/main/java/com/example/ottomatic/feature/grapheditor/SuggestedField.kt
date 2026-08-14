package com.example.ottomatic.feature.grapheditor

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.example.ottomatic.R
import com.example.ottomatic.domain.model.config.SuggestionSource
import com.example.ottomatic.domain.registry.Suggestions
import com.example.ottomatic.feature.mail.MailFolderChooser

/**
 * An **editable** field with a dropdown of suggestions — the widget for
 * `ConfigFieldType.SUGGESTED`.
 *
 * The shape `@MailFolder` invented for one field, now the one any node may declare. Its whole
 * character is in one sentence: **the suggestions never restrict what may be typed.** A
 * `binary_sensor` reports `on` or `off` and the dropdown says so; a `sensor` reports `21.4` and
 * the dropdown is empty — and the field behaves identically in both cases, because which one it
 * is depends on a *sibling field's* value rather than on the declaration. A widget that went
 * read-only when it happened to know the answers would change kind under the user's hands.
 *
 * There are **two ways suggestions arrive**, and the split is where the work is rather than in
 * the declaration: most sources answer from a hydrated registry synchronously, and a mail folder
 * list is an authenticated IMAP round trip that needs a ViewModel and a rendered failure. So a
 * local source draws a plain dropdown here, and a remote one hands off to its own chooser —
 * while the node author writes the same annotation either way.
 *
 * An empty suggestion list draws **no button at all** rather than an empty menu, which is the
 * degradation rule made visible: not knowing must look like an ordinary text field, never like
 * a chooser that has nothing in it.
 */
@Composable
internal fun SuggestedField(
    value: String,
    source: SuggestionSource,
    scope: List<String>,
    onValueChange: (String) -> Unit,
    labelSlot: @Composable () -> Unit,
    colors: TextFieldColors,
) {
    if (!Suggestions.isLocal(source)) {
        // The one source that cannot be answered without asking a server. Its chooser owns the
        // fetching, the spinner and the rendered failure, because "no account chosen" and "that
        // password needs re-typing" are things a dropdown cannot say.
        MailFolderChooser(
            value = value,
            accountId = scope.firstOrNull().orEmpty(),
            onValueChange = onValueChange,
            labelSlot = labelSlot,
            colors = colors,
        )
        return
    }

    var expanded by remember { mutableStateOf(false) }
    val options = Suggestions.of(source, scope)

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = labelSlot,
        colors = colors,
        singleLine = true,
        trailingIcon = {
            // No suggestions, no button. An empty menu would say "there is nothing" where the
            // truth is "nothing here can know" — the numeric-sensor case.
            if (options.isNotEmpty()) {
                IconButton(onClick = { expanded = true }) {
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = stringResource(R.string.config_show_suggestions),
                        tint = EditorColors.textSecondary,
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )

    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        options.forEach { option ->
            DropdownMenuItem(
                text = { Text(option, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                onClick = {
                    // The stored value is the suggestion itself, never a label. What a chooser
                    // *shows* may differ — Home Assistant renders a door sensor's `on` as
                    // "Open" — but `HaStateTrigger.matches` compares against the raw state, so
                    // writing anything else gives a trigger that reads perfectly and never fires.
                    onValueChange(option)
                    expanded = false
                },
            )
        }
    }
}
