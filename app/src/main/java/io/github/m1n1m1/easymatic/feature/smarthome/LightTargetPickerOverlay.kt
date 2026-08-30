package io.github.m1n1m1.easymatic.feature.smarthome

import androidx.compose.ui.res.stringResource
import io.github.m1n1m1.easymatic.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m1n1m1.easymatic.core.service.SmartHomeTargetKind
import io.github.m1n1m1.easymatic.domain.model.SmartHomeHub
import io.github.m1n1m1.easymatic.domain.model.SmartHomeRef
import io.github.m1n1m1.easymatic.domain.model.SmartHomeResource
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorOverlay

private val ROW_SHAPE = RoundedCornerShape(14.dp)
private const val SEARCH_THRESHOLD = 15

/**
 * Picks the light, room, zone or scene a light node acts on.
 *
 * One overlay for both `PickerKind`s, on `AppPickerField`'s precedent — the two ask
 * for different things from the same hub, and the only difference is which section
 * of the same snapshot is listed.
 *
 * **Rooms lead, then zones, then individual lights.** "Turn the kitchen off" is what
 * people mean far more often than "turn the third bulb in the kitchen off", and a
 * list that opens on forty bulb names buries the answer almost everybody wants.
 *
 * It reads the **cached snapshot**, never the bridge, so the first frame draws
 * instantly and works with the hub unplugged. A refresh is fired on open and its
 * failure ignored, for the reason stated on
 * [io.github.m1n1m1.easymatic.data.smarthome.SmartHomeSetup.refresh].
 */
@Composable
fun LightTargetPickerOverlay(
    viewModel: SmartHomeViewModel,
    kind: SmartHomeTargetKind,
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
            if (kind == SmartHomeTargetKind.SCENE) {
                R.string.smarthome_choose_a_scene
            } else {
                R.string.smarthome_choose_a_light
            },
        ),
        onClose = { picked?.let(onPick) ?: onDismiss() },
    ) { dismiss ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            val sections = state.hubs.flatMap { hub -> hub.sectionsFor(kind, query) }
            val total = sections.sumOf { it.rows.size }

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
                if (state.hubs.isEmpty()) {
                    item(key = "no-hub") {
                        EmptyState(
                            stringResource(R.string.smarthome_no_hub_yet),
                            stringResource(R.string.smarthome_add_a_hub),
                            viewModel::openKindChooser,
                        )
                    }
                } else if (total == 0) {
                    item(key = "empty") {
                        if (query.isBlank()) {
                            EmptyState(
                                stringResource(R.string.smarthome_nothing_read),
                                stringResource(R.string.smarthome_refresh),
                                viewModel::refreshAll,
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.smarthome_nothing_matches, query),
                                style = MaterialTheme.typography.bodyMedium,
                                color = EditorColors.textSecondary,
                                modifier = Modifier.padding(vertical = 16.dp),
                            )
                        }
                    }
                }
                sections.forEach { section ->
                    item(key = "h-${section.title}") { SectionHeader(section.title) }
                    section.rows.forEach { (hub, resource) ->
                        item(key = "${hub.id}-${resource.rid}") {
                            ResourceRow(
                                resource = resource,
                                selected = SmartHomeRef.parse(selected)?.rid == resource.rid,
                                onClick = {
                                    picked = SmartHomeRef.format(hub.id, resource.kind, resource.rid, resource.name)
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

private data class Section(val title: String, val rows: List<Pair<SmartHomeHub, SmartHomeResource>>)

/**
 * One hub's contribution, in the order somebody looks for things.
 *
 * The hub's own name is folded into each section title only when there is more than
 * one hub, because "Rooms — Living room bridge" on the single-bridge household every
 * user has is a word doing no work.
 */
@Composable
private fun SmartHomeHub.sectionsFor(kind: SmartHomeTargetKind, query: String): List<Section> {
    fun matching(of: SmartHomeTargetKind, predicate: (SmartHomeResource) -> Boolean = { true }) =
        resourcesOf(of)
            .filter(predicate)
            .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
            .map { this to it }

    // Resolved before the grouping, which is a plain lambda rather than a composition.
    val scenesHeading = stringResource(R.string.smarthome_scenes)

    val groups = if (kind == SmartHomeTargetKind.SCENE) {
        emptyList()
    } else {
        listOf(
            Section(
                stringResource(R.string.smarthome_rooms),
                matching(SmartHomeTargetKind.GROUP) { it.room != SmartHomeResource.ZONE },
            ),
            Section(
                stringResource(R.string.smarthome_zones),
                matching(SmartHomeTargetKind.GROUP) { it.room == SmartHomeResource.ZONE },
            ),
            Section(stringResource(R.string.smarthome_lights), matching(SmartHomeTargetKind.LIGHT)),
        )
    }
    val scenes = if (kind == SmartHomeTargetKind.SCENE) {
        matching(SmartHomeTargetKind.SCENE)
            .groupBy { (_, resource) -> resource.room.ifBlank { scenesHeading } }
            .map { (room, rows) -> Section(room, rows) }
    } else {
        emptyList()
    }
    return (groups + scenes).filter { it.rows.isNotEmpty() }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = EditorColors.textSecondary,
        modifier = Modifier.padding(top = 10.dp, start = 4.dp),
    )
}

@Composable
private fun ResourceRow(
    resource: SmartHomeResource,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = EditorColors.actionAccent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(EditorColors.nodeBackground)
            .border(if (selected) 2.dp else 1.dp, if (selected) accent else EditorColors.nodeBorder, ROW_SHAPE)
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = resource.name,
                style = MaterialTheme.typography.bodyLarge,
                color = EditorColors.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Said before the fact rather than discovered afterwards: Set colour on a
            // white-only bulb is accepted by the bridge and changes nothing.
            val note = when {
                resource.kind == SmartHomeTargetKind.LIGHT && !resource.supportsColour ->
                    stringResource(R.string.smarthome_white_only)
                resource.kind == SmartHomeTargetKind.GROUP -> stringResource(R.string.smarthome_everything_in_it)
                else -> resource.room
            }
            if (note.isNotBlank()) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = EditorColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(message: String, actionLabel: String, onAction: () -> Unit) {
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = EditorColors.textSecondary,
        )
        TextButton(onClick = onAction) { Text(actionLabel) }
    }
}


