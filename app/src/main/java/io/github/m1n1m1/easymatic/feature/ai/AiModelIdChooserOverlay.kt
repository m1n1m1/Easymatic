package io.github.m1n1m1.easymatic.feature.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m1n1m1.easymatic.R
import io.github.m1n1m1.easymatic.data.ai.AiModelInfo
import io.github.m1n1m1.easymatic.domain.model.AiModality
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorOverlay

private val ROW_SHAPE = RoundedCornerShape(14.dp)

/**
 * The listing from a connection's own server, filtered by what each model accepts.
 *
 * **An overlay rather than the dropdown this replaced**, because a row now carries three
 * things — a display name, an id, and what the model can be shown — and a `DropdownMenu`
 * item has room for one. The filter chips need somewhere to live too, and a menu that
 * scrolls behind a row of chips is not a menu.
 *
 * **The load-bearing rule is that an unknown row is never hidden.** Only OpenRouter
 * publishes `architecture.input_modalities`; Gemini publishes generation methods and no
 * modality field, and OpenAI, Anthropic and a self-hosted server publish little beyond
 * ids. So [AiModelInfo.modalities] is null on four providers out of five, and a filter
 * that read null as "does not accept" would empty the list on all four. It narrows what
 * is *known* and leaves what is not, and [UnknownNote] says so on screen rather than
 * leaving the user to wonder why the chips did nothing.
 *
 * This is `CapabilityStatus.UNKNOWN`'s stance and `PickerOptions`' degradation rule, in
 * the one place a user can actually see it: an empty answer means the question could not
 * be asked, never that the answer is no.
 *
 * It stays a chooser beside an editable field rather than becoming a read-only picker,
 * for `AiModelCatalog`'s stated reason: a listing offers what this key can reach right
 * now, and the server being set up is frequently switched off, unactivated, or serving no
 * listing at all.
 */
@Composable
internal fun AiModelIdChooserOverlay(
    models: List<AiModelInfo>,
    filter: Set<AiModality>,
    onToggleModality: (AiModality) -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val shown = models.filter { it.matches(filter) }
    val anyPublished = models.any { it.modalities != null }

    EditorOverlay(
        title = stringResource(R.string.ai_models_on_this_key),
        onClose = onDismiss,
    ) { dismiss ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "filters") {
                ModalityFilters(filter = filter, onToggle = onToggleModality)
            }
            if (!anyPublished) {
                item(key = "unknown") { UnknownNote() }
            }
            if (shown.isEmpty()) {
                item(key = "none") { NothingMatches() }
            }
            items(shown.size, key = { shown[it].id }) { index ->
                ModelRow(
                    model = shown[index],
                    onClick = {
                        onPick(shown[index].id)
                        dismiss()
                    },
                )
            }
        }
    }
}

/**
 * Whether this model survives [filter].
 *
 * Null modalities always pass. That is the whole contract of the field and the one line
 * of this file most worth not "tidying" into `modalities.orEmpty().containsAll(...)`,
 * which would read silence as refusal and hide every model on four providers.
 */
internal fun AiModelInfo.matches(filter: Set<AiModality>): Boolean =
    filter.isEmpty() || modalities == null || modalities.containsAll(filter)

/**
 * The chips, which offer sound and pictures and not the other three.
 *
 * Text is on everything, so a chip for it would never narrow anything; video and files
 * reach no node in this app, so filtering by them would offer a promise the graph cannot
 * keep. A row still *shows* all five, because saying truthfully what a model does is a
 * different job from offering to filter on it.
 */
@Composable
private fun ModalityFilters(filter: Set<AiModality>, onToggle: (AiModality) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        listOf(AiModality.AUDIO, AiModality.IMAGE).forEach { modality ->
            FilterChip(
                selected = modality in filter,
                onClick = { onToggle(modality) },
                label = { Text(stringResource(modality.labelRes())) },
                colors = FilterChipDefaults.filterChipColors(
                    labelColor = EditorColors.textSecondary,
                    selectedLabelColor = EditorColors.textPrimary,
                ),
            )
        }
    }
}

@Composable
private fun ModelRow(model: AiModelInfo, onClick: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(EditorColors.nodeBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = model.label.ifBlank { model.id },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = EditorColors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // The id under the name only when the two differ, so a provider that publishes no
        // display name does not print the same string twice.
        if (model.label.isNotBlank() && model.label != model.id) {
            Text(
                text = model.id,
                style = MaterialTheme.typography.bodySmall,
                color = EditorColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        model.modalities?.let { accepted ->
            // Resolved before the join: `stringResource` is composable and a
            // `joinToString` transform is not.
            val names = accepted.sorted().map { stringResource(it.labelRes()) }
            Text(
                text = names.joinToString(stringResource(R.string.ai_modality_separator)),
                style = MaterialTheme.typography.bodySmall,
                color = EditorColors.textSecondary,
            )
        }
    }
}

@Composable
private fun UnknownNote() {
    Note(stringResource(R.string.ai_provider_does_not_publish_capabilities))
}

@Composable
private fun NothingMatches() {
    Note(stringResource(R.string.ai_no_models_match_that_filter))
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = EditorColors.textSecondary,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    )
}
