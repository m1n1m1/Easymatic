package com.example.ottomatic.feature.grapheditor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.NodeTypeDefinition
import com.example.ottomatic.domain.registry.NodeTypeRegistry

/**
 * The node palette, shown either as the full catalogue (the `+` FAB) or
 * restricted to the types that can connect to a dragged port ([restrictedTo],
 * with [title] naming the origin). A restricted sheet offers a "Show all nodes"
 * escape hatch that widens it to the full catalogue.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod") // Single declarative sheet: search field + grouped, collapsible list.
fun NodePaletteSheet(
    onDismiss: () -> Unit,
    onPick: (NodeTypeDefinition) -> Unit,
    title: String? = null,
    restrictedTo: Set<NodeTypeId>? = null,
) {
    var query by remember { mutableStateOf("") }
    var collapsedCategories by remember { mutableStateOf(emptySet<NodeCategory>()) }
    var showAll by remember { mutableStateOf(false) }
    val restriction = restrictedTo?.takeUnless { showAll }
    val searchTerm = query.trim()
    val searching = searchTerm.isNotEmpty()
    val matchingDefinitions = NodeTypeRegistry.all
        .filter { restriction == null || it.typeId in restriction }
        .filter { definition ->
            searchTerm.isEmpty() || listOf(
                definition.displayName,
                definition.description,
                definition.typeId.value,
                definition.category.displayName,
            ).any { it.contains(searchTerm, ignoreCase = true) }
        }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            if (title != null) {
                PaletteTitleRow(
                    title = title,
                    onShowAll = if (restriction != null) ({ showAll = true }) else null,
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search nodes") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (searching) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            )
            LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
                if (matchingDefinitions.isEmpty()) {
                    item {
                        Text(
                            text = if (restriction != null) {
                                "No nodes can connect here"
                            } else {
                                "No nodes match your search"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                } else {
                    NodeKind.values().forEach { kind ->
                        val categoryGroups = NodeTypeRegistry.categoriesFor(kind).map { category ->
                            category to matchingDefinitions.filter { it.category == category }
                        }.filter { (_, definitions) -> definitions.isNotEmpty() }
                        if (categoryGroups.isNotEmpty()) {
                            item(key = "kind-${kind.name}") {
                                PaletteHeader(kindLabel(kind) + "s")
                            }
                            categoryGroups.forEach { (category, definitions) ->
                                item(key = "category-${category.name}") {
                                    PaletteCategory(
                                        category = category,
                                        definitions = definitions,
                                        isCollapsed = !searching && category in collapsedCategories,
                                        onToggle = {
                                            collapsedCategories = if (category in collapsedCategories) {
                                                collapsedCategories - category
                                            } else {
                                                collapsedCategories + category
                                            }
                                        },
                                        onPick = onPick,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Names the port a drag came from, with the escape hatch to the full catalogue. */
@Composable
private fun PaletteTitleRow(title: String, onShowAll: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (onShowAll != null) {
            TextButton(onClick = onShowAll) { Text("Show all") }
        }
    }
}

/** One collapsible category group with its node rows. */
@Composable
private fun PaletteCategory(
    category: NodeCategory,
    definitions: List<NodeTypeDefinition>,
    isCollapsed: Boolean,
    onToggle: () -> Unit,
    onPick: (NodeTypeDefinition) -> Unit,
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (isCollapsed) 0f else 180f,
        animationSpec = tween(220),
        label = "chevronRotation",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = category.displayName,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = definitions.size.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Icon(
            imageVector = Icons.Filled.ExpandMore,
            contentDescription = if (isCollapsed) {
                "Expand ${category.displayName}"
            } else {
                "Collapse ${category.displayName}"
            },
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.rotate(chevronRotation),
        )
    }
    AnimatedVisibility(
        visible = !isCollapsed,
        enter = expandVertically(tween(220)) + fadeIn(tween(180)),
        exit = shrinkVertically(tween(220)) + fadeOut(tween(180)),
    ) {
        Column {
            definitions.forEach { definition ->
                PaletteRow(definition, onPick)
            }
        }
    }
}

@Composable
private fun PaletteHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun PaletteRow(
    definition: NodeTypeDefinition,
    onPick: (NodeTypeDefinition) -> Unit,
) {
    val accent = accentColor(definition.kind)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick(definition) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
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
        Column {
            Text(
                text = definition.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = definition.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
