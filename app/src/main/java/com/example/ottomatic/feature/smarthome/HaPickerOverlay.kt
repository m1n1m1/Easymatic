package com.example.ottomatic.feature.smarthome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ottomatic.R
import com.example.ottomatic.domain.model.HomeAssistantRef
import com.example.ottomatic.domain.model.SmartHomeHub
import com.example.ottomatic.domain.model.SmartHomeKind
import com.example.ottomatic.feature.grapheditor.EditorColors
import com.example.ottomatic.feature.grapheditor.EditorOverlay

private val ROW_SHAPE = RoundedCornerShape(14.dp)
private const val SEARCH_THRESHOLD = 12

/** Which of the three Home Assistant lists a picker is showing. */
enum class HaPickerMode { ENTITY, SERVICE, HUB }

/**
 * Picks a Home Assistant entity, service or hub.
 *
 * One overlay for all three, on `LightTargetPickerOverlay`'s precedent — the same hub,
 * the same snapshot, three lists in it — and the reason that precedent applies is that
 * none of the three differs in anything but which list is walked.
 *
 * It reads the **cached snapshot**, never the server, so the first frame draws instantly
 * and works with the instance switched off. A refresh is fired on open and its failure
 * ignored, for the reason stated on
 * [com.example.ottomatic.data.smarthome.SmartHomeSetup.refresh]: a stale list of entities
 * is useful and an empty one is not.
 *
 * **The search box appears sooner than the light picker's** (twelve rows against
 * fifteen), and entities are grouped by domain rather than by area. Both follow from
 * scale: a household has perhaps a dozen lights and several hundred entities, so the
 * question stops being "which of these?" and becomes "where is the one I mean?" — and
 * `sensor` against `binary_sensor` against `climate` is the division that answers it,
 * where areas leave forty rows under "Living room".
 */
@Composable
fun HaPickerOverlay(
    viewModel: SmartHomeViewModel,
    mode: HaPickerMode,
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    var picked by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.refreshAll() }

    EditorOverlay(
        title = stringResource(
            when (mode) {
                HaPickerMode.ENTITY -> R.string.ha_choose_an_entity
                HaPickerMode.SERVICE -> R.string.ha_choose_a_service
                HaPickerMode.HUB -> R.string.ha_choose_a_hub
            },
        ),
        onClose = { picked?.let(onPick) ?: onDismiss() },
    ) { dismiss ->
        val hubs = state.hubs.filter { it.kind == SmartHomeKind.HOME_ASSISTANT }
        // The separator comes from resources like every other user-visible string:
        // a middle dot is punctuation, and punctuation is not the same in every script.
        val separator = stringResource(R.string.hue_text)
        val sections = hubs.flatMap { hub -> hub.sectionsFor(mode, query, separator) }
        val total = sections.sumOf { it.rows.size }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            if (total > SEARCH_THRESHOLD || query.isNotBlank()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.smarthome_search)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (hubs.isEmpty()) {
                    item(key = "no-hub") {
                        Text(
                            text = stringResource(R.string.ha_no_hub_yet),
                            style = MaterialTheme.typography.bodyMedium,
                            color = EditorColors.textSecondary,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                } else if (total == 0) {
                    item(key = "empty") {
                        Text(
                            text = if (query.isBlank()) {
                                stringResource(R.string.smarthome_nothing_read)
                            } else {
                                stringResource(R.string.smarthome_nothing_matches, query)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = EditorColors.textSecondary,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                }
                sections.forEach { section ->
                    item(key = "h-${section.title}") { HaSectionHeader(section.title) }
                    section.rows.forEach { row ->
                        item(key = "${row.hubId}-${row.id}") {
                            HaRow(
                                title = row.title,
                                subtitle = row.subtitle,
                                selected = HomeAssistantRef.parse(selected)?.id == row.id,
                                onClick = {
                                    picked = HomeAssistantRef.format(row.hubId, row.id, row.title)
                                    dismiss()
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    SmartHomeOverlays(state, viewModel)
}

private data class HaRowData(val hubId: String, val id: String, val title: String, val subtitle: String)

private data class HaSection(val title: String, val rows: List<HaRowData>)

/**
 * One hub's contribution, grouped the way somebody looks for the thing.
 *
 * The hub's own name is folded into each section title only when there is more than one
 * hub, on `LightTargetPickerOverlay`'s reasoning: "Sensors — Home Assistant" on the
 * single-instance household nearly everybody has is a word doing no work.
 */
private fun SmartHomeHub.sectionsFor(mode: HaPickerMode, query: String, separator: String): List<HaSection> {
    fun matches(vararg fields: String) =
        query.isBlank() || fields.any { it.contains(query, ignoreCase = true) }

    return when (mode) {
        HaPickerMode.HUB -> listOf(
            HaSection(
                name,
                listOf(HaRowData(id, "", name, host)).filter { matches(name, host) },
            ),
        ).filter { it.rows.isNotEmpty() }

        HaPickerMode.SERVICE -> services
            .filter { matches(it.id, it.name) }
            .groupBy { it.domain }
            .map { (domain, services) ->
                HaSection(
                    domain,
                    services.sortedBy { it.service }.map {
                        HaRowData(id, it.id, it.id, it.name.ifBlank { it.service })
                    },
                )
            }
            .sortedBy { it.title }

        HaPickerMode.ENTITY -> entities
            .filter { matches(it.entityId, it.name) }
            .groupBy { it.domain }
            .map { (domain, entities) ->
                HaSection(
                    domain,
                    entities.sortedBy { it.name.lowercase() }.map { entity ->
                        HaRowData(
                            hubId = id,
                            id = entity.entityId,
                            title = entity.name,
                            // The entity id is the subtitle rather than hidden: two
                            // lamps called "Ceiling" in different rooms are told apart
                            // by nothing else, and it is what a Home Assistant user
                            // recognises.
                            subtitle = listOfNotNull(
                                entity.entityId,
                                entity.area.takeIf { it.isNotBlank() },
                                entity.unit.takeIf { it.isNotBlank() },
                            ).joinToString(separator),
                        )
                    },
                )
            }
            .sortedBy { it.title }
    }
}

@Composable
private fun HaSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = EditorColors.textSecondary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun HaRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    val accent = EditorColors.actionAccent
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(EditorColors.nodeBackground)
            .border(1.dp, if (selected) accent else EditorColors.nodeBorder, ROW_SHAPE)
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = EditorColors.textPrimary,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = EditorColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
