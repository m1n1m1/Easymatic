package io.github.m1n1m1.easymatic.feature.translate

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.github.m1n1m1.easymatic.R
import io.github.m1n1m1.easymatic.feature.SettingsTopBar
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorOverlay
import java.util.Locale

/**
 * The languages this phone can translate: what is here, adding one, removing one.
 *
 * **This is where translation is set up**, not a report on what happened elsewhere, and that is the
 * difference from the Folder access screen it otherwise resembles. The Translate node offers only
 * the languages listed here, so a phone with an empty list has a node that cannot be configured —
 * which makes the Add button the primary thing on the screen rather than an afterthought under the
 * rows.
 *
 * **Downloading lives here and nowhere else**, which is `OnDeviceSetup.download`'s rule taken
 * literally: *"the only caller is a button somebody pressed"*. A node that fetched models as a side
 * effect of running would be spending somebody's data on a schedule, possibly at three in the
 * morning, and — if gated on Wi-Fi — would not fail off Wi-Fi but wait, wedging the run. Whoever
 * taps a language here has decided to spend the data, on a screen they opened in order to.
 *
 * **The rows are languages, but the note is about pairs.** ML Kit pivots through English, so
 * `de → fr` needs both German and French; deleting French breaks every macro translating into it
 * *and* every macro translating out of it. A list of single languages cannot show that on its own,
 * so it is said once at the bottom rather than implied per row.
 */
@Composable
fun TranslationModelsScreen(
    viewModel: TranslationModelsViewModel,
    onBack: () -> Unit,
) {
    val languages by viewModel.languages.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val working by viewModel.working.collectAsState()
    val error by viewModel.error.collectAsState()
    var adding by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EditorColors.canvasBackground),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SettingsTopBar(
                title = stringResource(R.string.translate_models_title),
                contentDescription = stringResource(R.string.nfc_back),
                onBack = onBack,
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "add") {
                    Button(
                        onClick = { adding = true },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                        Text(
                            text = stringResource(R.string.translate_models_add),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }

                if (working.isNotBlank()) {
                    item(key = "working") { DownloadingRow(language = working) }
                }

                // Three states rather than two, because "nothing downloaded" and "not asked yet"
                // read identically as an empty list and mean opposite things. See
                // `TranslationModelsViewModel.busy`.
                if (languages.isEmpty() && working.isBlank()) {
                    item(key = "empty") {
                        Text(
                            text = stringResource(
                                if (busy) R.string.translate_models_loading else R.string.translate_models_empty,
                            ),
                            color = EditorColors.textSecondary,
                            fontSize = 14.sp,
                        )
                    }
                }

                items(languages, key = { it }) { tag ->
                    ModelCard(tag = tag, enabled = !busy, onDelete = { viewModel.delete(tag) })
                }

                if (error.isNotBlank()) {
                    item(key = "error") {
                        Text(
                            text = error,
                            color = EditorColors.errorAccent,
                            fontSize = 13.sp,
                            // Tapping it dismisses it; there is nothing else it could usefully do,
                            // and a complaint with no way out stays on screen after the next
                            // successful download.
                            modifier = Modifier.clickable { viewModel.clearError() },
                        )
                    }
                }

                if (languages.isNotEmpty()) {
                    item(key = "note") { PivotNote() }
                }
            }
        }
    }

    if (adding) {
        AddLanguageOverlay(
            languages = viewModel.addable(),
            onPick = {
                viewModel.download(it)
                adding = false
            },
            onDismiss = { adding = false },
        )
    }
}

/**
 * The chooser for a language to download — everything supported that is not already here.
 *
 * Deliberately **not** `TranslateLanguagePickerField`'s overlay, though they look alike. That one
 * offers what is downloaded and links here; this one offers what is not and downloads. Sharing them
 * would mean one composable whose list, empty state and action all depend on which caller it had,
 * which is two screens wearing a trench coat.
 */
@Composable
private fun AddLanguageOverlay(
    languages: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by remember { mutableStateOf("") }

    EditorOverlay(title = stringResource(R.string.translate_models_add), onClose = onDismiss) { dismiss ->
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text(stringResource(R.string.translate_filter_languages)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )

        Text(
            text = stringResource(R.string.translate_models_size_note),
            color = EditorColors.textSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        // Sorted by what is shown rather than by tag, for the reason the picker gives.
        val rows = languages
            .map { it to languageLabel(it) }
            .filter { (tag, label) -> matchesFilter(filter, tag, label) }
            .sortedBy { (_, label) -> label.lowercase(Locale.getDefault()) }

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(rows, key = { (tag, _) -> tag }) { (tag, label) ->
                Text(
                    text = label,
                    color = EditorColors.textPrimary,
                    fontSize = 15.sp,
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
    }
}

@Composable
private fun DownloadingRow(language: String) {
    Card(colors = CardDefaults.cardColors(containerColor = EditorColors.nodeBackground)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // A spinner rather than a bar, and that is the library's limit rather than a choice:
            // ML Kit reports a model download as one Task with no progress. See
            // `TranslationModelsViewModel.working`.
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                text = stringResource(R.string.translate_models_downloading, languageLabel(language)),
                color = EditorColors.textPrimary,
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun ModelCard(tag: String, enabled: Boolean, onDelete: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = EditorColors.nodeBackground)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = languageName(tag),
                    color = EditorColors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
                Text(text = tag, color = EditorColors.textSecondary, fontSize = 12.sp)
            }
            TextButton(onClick = onDelete, enabled = enabled) {
                Text(stringResource(R.string.translate_models_delete))
            }
        }
    }
}

@Composable
private fun PivotNote() {
    Text(
        text = stringResource(R.string.translate_models_pivot_note),
        color = EditorColors.textSecondary,
        fontSize = 13.sp,
    )
}

/** Whether a row survives the filter. Matches the shown name and the stored tag alike. */
private fun matchesFilter(filter: String, tag: String, label: String): Boolean {
    if (filter.isBlank()) return true
    val needle = filter.trim().lowercase(Locale.getDefault())
    return label.lowercase(Locale.getDefault()).contains(needle) ||
        tag.lowercase(Locale.getDefault()).contains(needle)
}

/** A tag as its own language's name for it, falling back to the tag. */
private fun languageName(tag: String): String {
    val locale = runCatching { Locale.forLanguageTag(tag) }.getOrNull() ?: return tag
    return locale.getDisplayName(locale).ifBlank { tag }
}
