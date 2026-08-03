package com.example.ottomatic.feature.variables

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ottomatic.domain.model.VariableDeclaration
import com.example.ottomatic.feature.grapheditor.EditorColors

private val ROW_SHAPE = RoundedCornerShape(16.dp)

/**
 * One or both variable sets as one list, shared by the picker a node's config
 * opens, the editor's Variables surface and the standalone globals screen.
 *
 * The same economy [com.example.ottomatic.feature.geofence.GeofencePlaceList]
 * makes for places, with one section more. Which sections appear is the host's
 * call, and the two hosts answer it differently on purpose:
 *
 *  - **The picker shows both**, separated by a heading rather than by a tab,
 *    because the question there is "which of my variables is this?" and splitting
 *    the answer across two views makes the user look for it twice.
 *  - **The editor's Variables surface shows only this workflow's**, with
 *    [onOpenGlobals] as a way through to the shared set. A workflow's own variables
 *    are what that surface is about; listing every global under them makes a macro
 *    with two counters look like it has fifteen.
 *
 * [onSelect] is what tapping a row does — pick it, in the picker; edit it,
 * elsewhere — while the pencil always edits. [values] carries the live value per
 * ref spec where the host has one; an empty map simply omits that line.
 */
@Composable
@Suppress("LongParameterList") // Three hosts' worth of behaviour on one list; each parameter is one of them.
fun VariableList(
    locals: List<VariableDeclaration>,
    globals: List<VariableDeclaration>,
    onSelect: (spec: String) -> Unit,
    onEdit: (VariableScope, VariableDeclaration) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
    selectedSpec: String? = null,
    showLocals: Boolean = true,
    showGlobals: Boolean = true,
    onOpenGlobals: (() -> Unit)? = null,
    values: Map<String, String> = emptyMap(),
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "new") { NewVariableRow(onClick = onCreate) }
        onOpenGlobals?.let { open ->
            item(key = "globals-link") { GlobalVariablesRow(count = globals.size, onClick = open) }
        }
        if (showLocals) {
            section(
                key = "local",
                title = "This workflow",
                empty = "Nothing yet. A variable here belongs to this macro alone.",
                declarations = locals,
                scope = VariableScope.LOCAL,
                selectedSpec = selectedSpec,
                values = values,
                onSelect = onSelect,
                onEdit = onEdit,
            )
        }
        if (showGlobals) {
            section(
                key = "global",
                title = "Global",
                empty = "Nothing yet. A variable here is shared by every macro.",
                declarations = globals,
                scope = VariableScope.GLOBAL,
                selectedSpec = selectedSpec,
                values = values,
                onSelect = onSelect,
                onEdit = onEdit,
            )
        }
    }
}

@Suppress("LongParameterList") // Mirrors VariableList's own signature; it is one section of it.
private fun LazyListScope.section(
    key: String,
    title: String,
    empty: String,
    declarations: List<VariableDeclaration>,
    scope: VariableScope,
    selectedSpec: String?,
    values: Map<String, String>,
    onSelect: (String) -> Unit,
    onEdit: (VariableScope, VariableDeclaration) -> Unit,
) {
    item(key = "$key-header") { SectionHeader(title) }
    if (declarations.isEmpty()) {
        item(key = "$key-empty") {
            Text(
                text = empty,
                style = MaterialTheme.typography.bodyMedium,
                color = EditorColors.textSecondary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
        return
    }
    items(declarations, key = { "$key-${it.id}" }) { declaration ->
        val spec = specFor(scope, declaration)
        VariableRow(
            declaration = declaration,
            value = values[spec],
            selected = spec == selectedSpec,
            onClick = { onSelect(spec) },
            onEdit = { onEdit(scope, declaration) },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = EditorColors.textSecondary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
private fun NewVariableRow(onClick: () -> Unit) {
    val accent = EditorColors.triggerAccent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(accent.copy(alpha = 0.08f))
            .border(1.dp, accent.copy(alpha = 0.35f), ROW_SHAPE)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = accent)
        Text(
            text = "New variable",
            style = MaterialTheme.typography.bodyLarge,
            color = accent,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * The way through to the shared set from a workflow's own.
 *
 * It carries the count so that the globals are *represented* on this screen even
 * though they are not listed on it — "12 shared" is enough to tell someone their
 * `apiKey` is over there, which a bare chevron is not. Styled as an ordinary row
 * rather than as another accented one, because it is somewhere to go, not something
 * to do.
 */
@Composable
private fun GlobalVariablesRow(count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(EditorColors.nodeBackground)
            .border(1.dp, EditorColors.nodeBorder, ROW_SHAPE)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(EditorColors.actionAccent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Public,
                contentDescription = null,
                tint = EditorColors.actionAccent,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Global variables",
                style = MaterialTheme.typography.bodyLarge,
                color = EditorColors.textPrimary,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = if (count == 1) "1 shared by every macro" else "$count shared by every macro",
                style = MaterialTheme.typography.bodySmall,
                color = EditorColors.textSecondary,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = EditorColors.textSecondary,
        )
    }
}

@Composable
private fun VariableRow(
    declaration: VariableDeclaration,
    value: String?,
    selected: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
) {
    val accent = EditorColors.triggerAccent
    val borderColor = if (selected) accent else EditorColors.nodeBorder
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(EditorColors.nodeBackground)
            .border(if (selected) 2.dp else 1.dp, borderColor, ROW_SHAPE)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (declaration.constant) Icons.Filled.Lock else Icons.Filled.Tag,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = declaration.name,
                style = MaterialTheme.typography.bodyLarge,
                color = EditorColors.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = declaration.subtitle(value),
                style = MaterialTheme.typography.bodySmall,
                color = EditorColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = "Edit ${declaration.name}",
                tint = EditorColors.textSecondary,
            )
        }
    }
}

/**
 * The second line of a row: the type, then whichever of "what it holds" the host
 * can answer.
 *
 * A live [value] wins over the declared start, because when the dock can say what
 * a variable holds *right now* that is the only one of the two anybody is reading
 * the panel for. A constant says so instead of pretending its value might change.
 */
internal fun VariableDeclaration.subtitle(value: String?): String {
    val what = when {
        constant -> "constant · ${initialValue.ifBlank { "empty" }}"
        value != null -> "= ${value.ifBlank { "empty" }}"
        initialValue.isNotBlank() -> "starts at $initialValue"
        else -> "unset"
    }
    return "${type.label()} · $what"
}
