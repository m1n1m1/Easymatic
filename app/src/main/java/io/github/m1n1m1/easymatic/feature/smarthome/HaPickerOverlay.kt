package io.github.m1n1m1.easymatic.feature.smarthome

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.m1n1m1.easymatic.R
import io.github.m1n1m1.easymatic.domain.model.HaScope
import io.github.m1n1m1.easymatic.domain.model.HomeAssistantRef
import io.github.m1n1m1.easymatic.domain.model.SmartHomeHub
import io.github.m1n1m1.easymatic.domain.model.SmartHomeKind
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorOverlay

private val ROW_SHAPE = RoundedCornerShape(14.dp)
private const val SEARCH_THRESHOLD = 12

/** Which of the four Home Assistant lists a picker is showing. */
enum class HaPickerMode { ENTITY, SERVICE, TRIGGER, HUB }

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
 * [io.github.m1n1m1.easymatic.data.smarthome.SmartHomeSetup.refresh]: a stale list of entities
 * is useful and an empty one is not.
 *
 * **The search box appears sooner than the light picker's** (twelve rows against
 * fifteen), and entities are grouped by domain rather than by area. Both follow from
 * scale: a household has perhaps a dozen lights and several hundred entities, so the
 * question stops being "which of these?" and becomes "where is the one I mean?" — and
 * `sensor` against `binary_sensor` against `climate` is the division that answers it,
 * where areas leave forty rows under "Living room".
 */
// Inherent: one overlay serving three modes, each with its own empty state, plus the scope
// toggle. Splitting it would separate the list from the sentence explaining why it is empty.
@Suppress("CyclomaticComplexMethod")
@Composable
fun HaPickerOverlay(
    viewModel: SmartHomeViewModel,
    mode: HaPickerMode,
    scope: HaScope,
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    var picked by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    // Scoped by default, because that is the point — but never *only* scoped. See below.
    var scoped by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) { viewModel.refreshAll() }
    // The one question the snapshot cannot answer: which triggers and services *this entity*
    // accepts. Asked once per entity, and only when there is an entity to ask about.
    LaunchedEffect(mode, scope.hubId, scope.entityId) {
        viewModel.narrowHomeAssistant(mode, scope.hubId, scope.entityId)
    }

    EditorOverlay(
        title = stringResource(
            when (mode) {
                HaPickerMode.ENTITY -> R.string.ha_choose_an_entity
                HaPickerMode.SERVICE -> R.string.ha_choose_a_service
                HaPickerMode.TRIGGER -> R.string.ha_choose_a_trigger
                HaPickerMode.HUB -> R.string.ha_choose_a_hub
            },
        ),
        onClose = { picked?.let(onPick) ?: onDismiss() },
    ) { dismiss ->
        val answer = state.narrowingFor(mode, scope)
        // Narrow only on an answer that arrived and had something in it. A request still in
        // flight, a socket that is down and an instance too old to know the command all leave
        // the wide list up — "cannot narrow", never "nothing applies".
        val allowed = answer.ids.takeIf { answer.answered && it.isNotEmpty() }
        val narrowing = scoped && (!scope.isEmpty || allowed != null)
        val hubs = state.hubs
            .filter { it.kind == SmartHomeKind.HOME_ASSISTANT }
            .filter { !narrowing || scope.hubId.isBlank() || it.id == scope.hubId }
        // The separator comes from resources like every other user-visible string:
        // a middle dot is punctuation, and punctuation is not the same in every script.
        val separator = stringResource(R.string.hue_text)
        // **The built-in row, and it is not decoration.** Everything below it comes from the
        // instance, so an entity no integration declares triggers for — or an instance too old
        // to be asked — would leave the list empty and the node unconfigurable. This one is
        // serviced by the `state_changed` stream the cache already needs, so it works
        // everywhere, and it is what the node used to do before it grew a trigger list.
        val builtIn = if (mode == HaPickerMode.TRIGGER) {
            listOf(
                HaSection(
                    stringResource(R.string.ha_trigger_built_in),
                    listOf(
                        HaRowData(
                            hubId = scope.hubId,
                            id = "",
                            title = stringResource(R.string.ha_any_state_change),
                            subtitle = stringResource(R.string.ha_any_state_change_hint),
                        ),
                    ),
                ),
            )
        } else {
            emptyList()
        }
        val sections = builtIn + hubs.flatMap { hub ->
            hub.sectionsFor(
                mode = mode,
                query = query,
                separator = separator,
                scope = if (narrowing) scope else HaScope(),
                allowed = allowed.takeIf { narrowing },
            )
        }
        val total = sections.sumOf { it.rows.size }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            // **The escape hatch, and it is not optional.** A read-only picker is justified
            // on the answer set being knowable and complete, and a scoped list is by
            // definition not complete. Without a way back to the unscoped one, the price of a
            // single wrong `target` field in somebody's custom integration is a service
            // unreachable by any means at all.
            // Not offered for triggers: there is no wider list to show. A trigger list is a
            // fact about one entity, so "everything" would be the same rows again.
            if (!scope.isEmpty && mode != HaPickerMode.TRIGGER) {
                TextButton(
                    onClick = { scoped = !scoped },
                    modifier = Modifier.padding(horizontal = 12.dp),
                ) {
                    Text(
                        stringResource(
                            if (scoped) R.string.ha_show_everything else R.string.ha_show_matching,
                        ),
                    )
                }
            }

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
                                // A trigger is stored as the bare id Home Assistant uses,
                                // not as a reference: the hub and the entity are already
                                // decided by the field beside it, so wrapping it would
                                // record the same two facts twice and let them disagree.
                                selected = if (mode == HaPickerMode.TRIGGER) {
                                    selected == row.id
                                } else {
                                    HomeAssistantRef.parse(selected)?.id == row.id
                                },
                                onClick = {
                                    picked = if (mode == HaPickerMode.TRIGGER) {
                                        row.id
                                    } else {
                                        HomeAssistantRef.format(row.hubId, row.id, row.title)
                                    }
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

/** What Home Assistant answered about the entity in scope, for the list being shown. */
private fun SmartHomeUiState.narrowingFor(mode: HaPickerMode, scope: HaScope): HaNarrowing {
    val key = "${scope.hubId}|${scope.entityId}"
    return when (mode) {
        HaPickerMode.TRIGGER -> haTriggers[key]
        HaPickerMode.SERVICE -> haServices[key]
        HaPickerMode.ENTITY, HaPickerMode.HUB -> null
    } ?: HaNarrowing()
}

/**
 * `media_player.started_playing` as "Started playing".
 *
 * Deliberately not translated, and the id is kept underneath it as the subtitle. These are the
 * server's own vocabulary — the same words the web interface prints — so a translated title
 * would stop matching what the user has already seen there, and the set is open-ended in a way
 * no `values-de/` could keep up with: every integration installed adds to it.
 */
private fun prettyTrigger(id: String): String =
    id.substringAfter('.').replace('_', ' ').replaceFirstChar { it.uppercase() }

/**
 * One hub's contribution, grouped the way somebody looks for the thing.
 *
 * The hub's own name is folded into each section title only when there is more than one
 * hub, on `LightTargetPickerOverlay`'s reasoning: "Sensors — Home Assistant" on the
 * single-instance household nearly everybody has is a word doing no work.
 */
private fun SmartHomeHub.sectionsFor(
    mode: HaPickerMode,
    query: String,
    separator: String,
    scope: HaScope,
    /**
     * The ids Home Assistant said apply to the entity in scope, or null for *cannot narrow*.
     *
     * For a service this is a filter over a list the snapshot already holds. For a trigger it
     * is the **whole list** — nothing is cached for triggers and nothing could be, since they
     * are a fact about one entity rather than about the instance.
     */
    allowed: Set<String>?,
): List<HaSection> {
    fun matches(vararg fields: String) =
        query.isBlank() || fields.any { it.contains(query, ignoreCase = true) }

    return when (mode) {
        HaPickerMode.TRIGGER -> allowed.orEmpty()
            .filter { matches(it, prettyTrigger(it)) }
            .groupBy { it.substringBefore('.', missingDelimiterValue = "") }
            .map { (domain, triggers) ->
                HaSection(
                    domain.ifBlank { name },
                    triggers.sorted().map { HaRowData(id, it, prettyTrigger(it), it) },
                )
            }
            .sortedBy { it.title }


        HaPickerMode.HUB -> listOf(
            HaSection(
                name,
                listOf(HaRowData(id, "", name, host)).filter { matches(name, host) },
            ),
        ).filter { it.rows.isNotEmpty() }

        HaPickerMode.SERVICE -> services
            // Home Assistant's own answer when it gave one — `get_services_for_target` knows
            // about `supported_features` and about integrations that publish no `target` at
            // all, neither of which can be worked out from this side. The domain filter below
            // is the fallback for a socket that is down, and the rule (and the degradation
            // with it) lives on HaService: empty metadata narrows nothing and must never
            // narrow to nothing.
            .filter { if (allowed != null) it.id in allowed else it.offersFor(scope.entityDomain) }
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
        )
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = EditorColors.textSecondary,
            )
        }
    }
}
