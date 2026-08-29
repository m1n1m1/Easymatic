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
import java.util.Locale

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
                text = { Text(labelFor(source, option), maxLines = 1, overflow = TextOverflow.Ellipsis) },
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

/**
 * What a suggestion should *read* as, which is not always what it is.
 *
 * **A language tag is the one source whose values are unreadable**, and this is where that
 * is fixed rather than in `Suggestions`: the stored value must stay the tag — the comment
 * on the click handler above says why, and it applies with full force here, since
 * `RecognizerIntent.EXTRA_LANGUAGE` takes `de-DE` and nothing else — so only the rendering
 * may change. `Locale.forLanguageTag` is plain JVM and needs no table of our own.
 *
 * The tag is kept beside the name rather than replaced by it. Two of them can render the
 * same in a given display language, and somebody who knows they want `en-GB` rather than
 * `en-US` has to be able to see which row is which.
 */
@Composable
private fun labelFor(source: SuggestionSource, option: String): String = when (source) {
    SuggestionSource.SPEECH_LANGUAGE, SuggestionSource.RECOGNITION_LANGUAGE -> languageLabel(option)
    else -> option
}

@Composable
private fun languageLabel(tag: String): String {
    val locale = runCatching { Locale.forLanguageTag(tag) }.getOrNull()
    val name = locale?.getDisplayName(locale).orEmpty()
    // A tag the platform cannot parse renders as itself, which is still better than blank.
    if (name.isBlank() || name == tag) return tag
    // Through a resource rather than an interpolation, because the separator is punctuation
    // and punctuation is translated — a locale that does not use a spaced em dash should not
    // inherit one from this file.
    return stringResource(R.string.grapheditor_language_name_and_tag, name, tag)
}
