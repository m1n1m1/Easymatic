package com.example.ottomatic.feature.grapheditor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ottomatic.R
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.NodeTypeDefinition
import com.example.ottomatic.domain.registry.NodeTypeRegistry
import com.example.ottomatic.domain.registry.PaletteGroup
import com.example.ottomatic.domain.registry.PluginNodes
import com.example.ottomatic.domain.registry.matchesSearch
import com.example.ottomatic.feature.i18n.label
import com.example.ottomatic.feature.i18n.labelRes
import com.example.ottomatic.feature.i18n.rememberNodeText

/**
 * Below this many matching nodes the whole palette opens expanded: a list this
 * short is its own overview, so collapsing it would only cost a tap.
 */
private const val AUTO_EXPAND_THRESHOLD = 8

private val CARD_SHAPE = RoundedCornerShape(18.dp)

/**
 * The node palette, shown either as the full catalogue (the `+` FAB) or
 * restricted to a set of types ([restrictedTo], with [title] naming why).
 *
 * Each node kind is one accent-tinted card holding its categories, which start
 * collapsed — see [AUTO_EXPAND_THRESHOLD] for when they do not.
 *
 * **[canWiden] is what tells the two kinds of restriction apart**, and it is the
 * reason this takes a flag rather than inferring one. A palette restricted to what
 * can connect to a dragged port is a *suggestion*: everything it hides is still a
 * legal node to place, so hiding it without a way back would be the app deciding what
 * the user may build, and "Show all" is the escape hatch. A palette restricted to what
 * can run as an AI tool is a *rule*: a trigger or a loop offered there would produce a
 * tool that silently does nothing, which is the worst failure that feature has. The
 * first widens; the second must not.
 */
@Composable
@Suppress("LongMethod") // Single declarative surface: search field + grouped, collapsible list.
fun NodePaletteOverlay(
    onDismiss: () -> Unit,
    onPick: (NodeTypeDefinition) -> Unit,
    title: String? = null,
    restrictedTo: Set<NodeTypeId>? = null,
    canWiden: Boolean = true,
) {
    var query by remember { mutableStateOf("") }
    var expandedGroups by remember { mutableStateOf(emptySet<PaletteGroup>()) }
    var showAll by remember { mutableStateOf(false) }
    val restriction = restrictedTo?.takeUnless { showAll && canWiden }
    val searchTerm = query.trim()
    val searching = searchTerm.isNotEmpty()
    // `NodeTypeRegistry.all` is no longer a constant: it is the compiled registries plus
    // whichever plugins are enabled right now. Compose cannot observe a plain function
    // call, so the plugin set is collected and used as the key — install, uninstall or
    // a flick of the enable switch then redraws the palette, and nothing else does,
    // because nothing else can change it.
    val plugins by PluginNodes.entries.collectAsState()
    val availableTypes = remember(plugins) { NodeTypeRegistry.all }
    val nodeText = rememberNodeText()
    val resources = LocalContext.current.resources
    val matchingDefinitions = availableTypes
        .filter { restriction == null || it.typeId in restriction }
        // The translated name, description and category join the declaration's English
        // in the haystack rather than replacing it — see `matchesSearch`.
        .filter {
            it.matchesSearch(
                searchTerm,
                listOf(
                    nodeText.name(it),
                    nodeText.description(it),
                    resources.getString(it.category.labelRes()),
                ),
            )
        }
    // Searching and short result sets expand everything without touching
    // [expandedGroups], so clearing the search restores what the user opened.
    val expandAll = searching || matchingDefinitions.size <= AUTO_EXPAND_THRESHOLD

    val listState = rememberLazyListState()
    // Picking closes the palette too, so it goes through the same exit
    // animation: the pick is held here and applied once the overlay is gone.
    var picked by remember { mutableStateOf<NodeTypeDefinition?>(null) }

    EditorOverlay(
        title = title ?: stringResource(R.string.grapheditor_add_node_2),
        onClose = { picked?.let(onPick) ?: onDismiss() },
        action = if (restriction != null && canWiden) {
            { TextButton(onClick = { showAll = true }) { Text(stringResource(R.string.grapheditor_show_all)) } }
        } else {
            null
        },
    ) { dismiss ->
        val pick: (NodeTypeDefinition) -> Unit = { definition ->
            picked = definition
            dismiss()
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding(),
        ) {
            PaletteSearchField(
                query = query,
                onQueryChange = { query = it },
                onClear = { query = "" },
            )
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (matchingDefinitions.isEmpty()) {
                    item { PaletteEmptyText(restricted = restriction != null, searching = searching) }
                } else {
                    NodeKind.values().forEach { kind ->
                        // Groups rather than categories: a plugin's nodes are headed with
                        // the plugin's own name, so somebody about to remove one can see
                        // what will go with it.
                        val matching = matchingDefinitions.toSet()
                        val groups = NodeTypeRegistry.groupsFor(kind).map { group ->
                            group to NodeTypeRegistry.nodesIn(group).filter { it in matching }
                        }.filter { (_, definitions) -> definitions.isNotEmpty() }
                        if (groups.isNotEmpty()) {
                            item(key = "kind-${kind.name}") {
                                PaletteKindCard(
                                    kind = kind,
                                    groups = groups,
                                    isExpanded = { group ->
                                        expandAll || group in expandedGroups
                                    },
                                    onToggle = { group ->
                                        expandedGroups = if (group in expandedGroups) {
                                            expandedGroups - group
                                        } else {
                                            expandedGroups + group
                                        }
                                    },
                                    onPick = pick,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Why the list is empty.
 *
 * A search that found nothing says so even inside a restricted palette: "nothing can
 * connect here" would be a statement about the *port* when the user narrowed the list
 * themselves — and it is wrong outright for a palette restricted for some other
 * reason, such as what can run as an AI tool.
 */
@Composable
private fun PaletteEmptyText(restricted: Boolean, searching: Boolean) {
    Text(
        text = if (restricted && !searching) {
            stringResource(R.string.grapheditor_no_nodes_can_connect_here)
        } else {
            stringResource(R.string.grapheditor_no_nodes_match_your_search)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = EditorColors.textSecondary,
        modifier = Modifier.padding(20.dp),
    )
}

/** Search field, themed to the overlay's fixed dark chrome rather than the app's dynamic colors. */
@Composable
private fun PaletteSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text(stringResource(R.string.grapheditor_search_nodes)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.grapheditor_clear_search))
                }
            }
        },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = EditorColors.textPrimary,
            unfocusedTextColor = EditorColors.textPrimary,
            cursorColor = EditorColors.portSnap,
            focusedBorderColor = EditorColors.portSnap,
            unfocusedBorderColor = EditorColors.chromeBorder,
            focusedLabelColor = EditorColors.portSnap,
            unfocusedLabelColor = EditorColors.textSecondary,
            focusedLeadingIconColor = EditorColors.textSecondary,
            unfocusedLeadingIconColor = EditorColors.textSecondary,
            focusedTrailingIconColor = EditorColors.textSecondary,
            unfocusedTrailingIconColor = EditorColors.textSecondary,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

/**
 * One node kind as a tinted card: its accent washes the background, border,
 * header glyph and count, so Triggers / Actions / Conditions read as three
 * distinct zones rather than three headings.
 */
@Composable
private fun PaletteKindCard(
    kind: NodeKind,
    groups: List<Pair<PaletteGroup, List<NodeTypeDefinition>>>,
    isExpanded: (PaletteGroup) -> Boolean,
    onToggle: (PaletteGroup) -> Unit,
    onPick: (NodeTypeDefinition) -> Unit,
) {
    val accent = accentColor(kind)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(CARD_SHAPE)
            .background(accent.copy(alpha = 0.06f))
            .border(1.dp, accent.copy(alpha = 0.30f), CARD_SHAPE),
    ) {
        PaletteKindHeader(
            kind = kind,
            accent = accent,
            count = groups.sumOf { (_, definitions) -> definitions.size },
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(accent.copy(alpha = 0.16f)),
        )
        groups.forEach { (group, definitions) ->
            PaletteGroupRows(
                group = group,
                definitions = definitions,
                accent = accent,
                isExpanded = isExpanded(group),
                onToggle = { onToggle(group) },
                onPick = onPick,
            )
        }
    }
}

@Composable
private fun PaletteKindHeader(kind: NodeKind, accent: Color, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(accent.copy(alpha = 0.20f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = kindIcon(kind),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = stringResource(kindLabelPluralRes(kind)).uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = accent,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(accent.copy(alpha = 0.16f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** One collapsible palette group with its node rows. */
@Composable
private fun PaletteGroupRows(
    group: PaletteGroup,
    definitions: List<NodeTypeDefinition>,
    accent: Color,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onPick: (NodeTypeDefinition) -> Unit,
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(220),
        label = "chevronRotation",
    )
    // The only moving color cue: an open category tints towards its kind.
    val trailingColor = if (isExpanded) accent else EditorColors.textSecondary
    // Resolved once: the heading and both content descriptions name the same thing.
    val groupName = group.label()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = groupName,
            style = MaterialTheme.typography.titleSmall,
            color = EditorColors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = definitions.size.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = trailingColor,
            modifier = Modifier.padding(end = 8.dp),
        )
        Icon(
            imageVector = Icons.Filled.ExpandMore,
            contentDescription = if (isExpanded) {
                stringResource(R.string.cd_palette_collapse_group, groupName)
            } else {
                stringResource(R.string.cd_palette_expand_group, groupName)
            },
            tint = trailingColor,
            modifier = Modifier.rotate(chevronRotation),
        )
    }
    AnimatedVisibility(
        visible = isExpanded,
        enter = expandVertically(tween(220)) + fadeIn(tween(180)),
        exit = shrinkVertically(tween(220)) + fadeOut(tween(180)),
    ) {
        Column {
            definitions.forEach { definition ->
                PaletteRow(definition, accent, onPick)
            }
        }
    }
}

@Composable
private fun PaletteRow(
    definition: NodeTypeDefinition,
    accent: Color,
    onPick: (NodeTypeDefinition) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick(definition) }
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = nodeIcon(definition.icon),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp),
            )
        }
        val nodeText = rememberNodeText()
        Column {
            Text(
                text = nodeText.name(definition),
                style = MaterialTheme.typography.bodyLarge,
                color = EditorColors.textPrimary,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = nodeText.description(definition),
                style = MaterialTheme.typography.bodySmall,
                color = EditorColors.textSecondary,
            )
        }
    }
}
