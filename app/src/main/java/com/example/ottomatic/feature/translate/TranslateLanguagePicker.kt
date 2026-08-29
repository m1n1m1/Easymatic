package com.example.ottomatic.feature.translate

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ottomatic.R
import com.example.ottomatic.domain.registry.TranslateLanguages
import com.example.ottomatic.feature.grapheditor.EditorColors
import com.example.ottomatic.feature.grapheditor.EditorOverlay
import com.example.ottomatic.feature.grapheditor.PickerFieldChrome
import java.util.Locale

/**
 * The chooser behind `PickerKind.TRANSLATE_LANGUAGE`.
 *
 * **It offers the languages that are downloaded, not the ones the library supports**, and that is
 * the decision the whole node rests on. Translation cannot happen at all without the model being
 * present, so a chooser listing all sixty would be listing mostly configurations that fail on their
 * first run — the `RECOGNITION_LANGUAGE` mistake `SpeechLanguages.forListening` describes, where
 * "offering the long list is offering mostly wrong answers", except that here the wrong answers do
 * not merely degrade, they refuse.
 *
 * **So the empty state is a route rather than an apology.** A phone that has downloaded nothing
 * gets a chooser with no rows, which would be a dead end if it were all there was; the link to the
 * Translation models screen is what makes it a step. That link is also why this picker is the one
 * that leaves the editor: adding a language is a download, and downloads belong on the screen
 * somebody opened in order to spend the data. See [TranslationModelLibrary].
 *
 * **What is stored is the tag; what is shown is the language.** `Locale.forLanguageTag(tag)`
 * `.getDisplayName(locale)` is already correct in all eight locales, which is the whole reason
 * these languages are not an `enum` — that would have cost a generated string key per language per
 * field to restate, in eight languages, what the JVM already knows. The tag is kept beside the name
 * rather than replaced by it, on `SuggestedField.languageLabel`'s reasoning: two languages can
 * render alike in a given display language, and somebody who knows they want `nb` rather than `no`
 * has to be able to see which row is which.
 */
@Composable
internal fun TranslateLanguagePickerField(
    value: String,
    onValueChange: (String) -> Unit,
    labelSlot: @Composable () -> Unit,
    colors: TextFieldColors,
) {
    var picking by remember { mutableStateOf(false) }
    val library = LocalTranslationModels.current

    // The registry is the fallback rather than the source, so the field still renders in a preview
    // or a test where nothing is provided.
    val downloaded = library?.models?.languages?.collectAsState()?.value
        ?: TranslateLanguages.downloaded()

    PickerFieldChrome(
        display = if (value.isBlank()) "" else languageLabel(value),
        // Never disabled, unlike the pickers whose libraries can genuinely be unreachable: an empty
        // list here is exactly the case the user most needs to open the chooser for, because that
        // is where the way to fix it is.
        icon = Icons.Filled.Translate,
        enabled = true,
        onTap = { picking = true },
        labelSlot = labelSlot,
        colors = colors,
    )

    if (picking) {
        TranslateLanguagePickerOverlay(
            languages = downloaded,
            selected = value,
            onManage = library?.openSettings,
            onPick = {
                onValueChange(it)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun TranslateLanguagePickerOverlay(
    languages: List<String>,
    selected: String,
    onManage: (() -> Unit)?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by remember { mutableStateOf("") }

    EditorOverlay(
        title = stringResource(R.string.translate_pick_language),
        onClose = onDismiss,
    ) { dismiss ->
        // Only once the list is long enough to be worth narrowing. A filter box above three rows
        // is furniture, and this list is three rows for most people.
        if (languages.size > FILTER_THRESHOLD) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                label = { Text(stringResource(R.string.translate_filter_languages)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }

        // Sorted by what is *shown* rather than by tag, because the list is read as language names
        // — an alphabetical list of tags puts German under D and Dutch under N.
        val rows = languages
            .map { it to languageLabel(it) }
            .filter { (tag, label) -> matches(filter, tag, label) }
            .sortedBy { (_, label) -> label.lowercase(Locale.getDefault()) }

        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            if (languages.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.translate_no_languages_downloaded),
                        color = EditorColors.textSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    )
                }
            }
            items(rows, key = { (tag, _) -> tag }) { (tag, label) ->
                Text(
                    text = label,
                    color = EditorColors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = if (tag == selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onPick(tag)
                            dismiss()
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
                HorizontalDivider(color = EditorColors.chromeBorder)
            }
        }

        if (onManage != null) {
            ManageLanguagesRow(
                onClick = {
                    // Dismissed first so the overlay's exit animation plays before the screen
                    // changes under it — the deferred-pick idiom every chooser here uses, for the
                    // same reason.
                    dismiss()
                    onManage()
                },
            )
        }
    }
}

/**
 * The way out to the Translation models screen.
 *
 * At the **bottom** rather than the top, which is the opposite of `AiModelPickerOverlay`'s Add row
 * and deliberate: that row adds something without leaving, so it belongs where the eye lands first.
 * This one abandons the chooser and changes screen, which is not what somebody who came here to
 * pick a language usually wants offered before the languages themselves.
 */
@Composable
private fun ManageLanguagesRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = EditorColors.actionAccent,
        )
        Column {
            Text(
                text = stringResource(R.string.translate_manage_languages),
                color = EditorColors.actionAccent,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
            Text(
                text = stringResource(R.string.translate_manage_languages_hint),
                color = EditorColors.textSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

/** Whether a row survives the filter. Matches the shown name and the stored tag alike. */
private fun matches(filter: String, tag: String, label: String): Boolean {
    if (filter.isBlank()) return true
    val needle = filter.trim().lowercase(Locale.getDefault())
    return label.lowercase(Locale.getDefault()).contains(needle) ||
        tag.lowercase(Locale.getDefault()).contains(needle)
}

/**
 * A tag as "German — de".
 *
 * `SuggestedField.languageLabel`'s rendering, deliberately duplicated rather than shared: that one
 * is a `@Composable` reading a string resource for its separator and lives in the suggestion
 * widget's file, and hoisting it would drag the suggestion machinery into a picker that has nothing
 * to do with it. Both go through `Locale`, so they cannot disagree about the name.
 */
@Composable
internal fun languageLabel(tag: String): String {
    val locale = runCatching { Locale.forLanguageTag(tag) }.getOrNull()
    val name = locale?.getDisplayName(locale).orEmpty()
    if (name.isBlank() || name == tag) return tag
    return stringResource(R.string.grapheditor_language_name_and_tag, name, tag)
}

/** Above how many rows a filter box earns its place. */
private const val FILTER_THRESHOLD = 8
