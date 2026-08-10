package com.example.ottomatic.feature.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Psychology
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
import com.example.ottomatic.domain.model.AiConnection
import com.example.ottomatic.feature.grapheditor.EditorColors

private val ROW_SHAPE = RoundedCornerShape(16.dp)

/**
 * The connection library as a list, shared by the standalone AI screen and the
 * picker every Ask AI node's Connection field opens — `MailAccountList`'s shape for
 * its reasons.
 *
 * There is **no "any connection" row**, and the absence is deliberate on that
 * list's own argument: blank is a real answer to "which tag should fire this?" and
 * is not one to "which key should this be billed to". A node with no connection
 * chosen is unconfigured, and the Problems panel says so.
 */
@Composable
fun AiConnectionList(
    connections: List<AiConnection>,
    onSelect: (AiConnection) -> Unit,
    onEdit: (AiConnection) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    selectedId: String? = null,
    needsKey: (String) -> Boolean = { false },
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "add") { AddConnectionRow(onClick = onAdd) }
        if (connections.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = "No connections yet. Add one to use the Ask AI node. It takes a free " +
                        "API key from Google AI Studio — the form walks you through getting one, " +
                        "and opens the page for you.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EditorColors.textSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
                )
            }
        }
        items(connections, key = { it.id }) { connection ->
            ConnectionRow(
                connection = connection,
                selected = connection.id == selectedId,
                needsKey = needsKey(connection.id),
                onClick = { onSelect(connection) },
                onEdit = { onEdit(connection) },
            )
        }
    }
}

@Composable
private fun AddConnectionRow(onClick: () -> Unit) {
    val accent = EditorColors.actionAccent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .border(1.dp, accent.copy(alpha = 0.5f), ROW_SHAPE)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
        Text(
            text = "Add connection",
            style = MaterialTheme.typography.bodyLarge,
            color = accent,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ConnectionRow(
    connection: AiConnection,
    selected: Boolean,
    needsKey: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(EditorColors.nodeBackground)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) EditorColors.actionAccent else EditorColors.nodeBorder,
                shape = ROW_SHAPE,
            )
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Psychology,
            contentDescription = null,
            tint = EditorColors.actionAccent,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = connection.name,
                style = MaterialTheme.typography.bodyLarge,
                color = EditorColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The provider rather than the key, which is never shown. A restored
            // phone is the case worth badging: the connection is intact and only
            // the key is gone, which no amount of correct code prevents.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (needsKey) {
                    Icon(
                        imageVector = Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = EditorColors.warnAccent,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    text = if (needsKey) "Key needs pasting in again" else providerLabel(connection),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (needsKey) EditorColors.warnAccent else EditorColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = "Edit", tint = EditorColors.textSecondary)
        }
    }
}

/** The provider's own name, as the enum's `@Label` gives it. */
private fun providerLabel(connection: AiConnection): String = when (connection.provider) {
    com.example.ottomatic.domain.model.AiProvider.GEMINI -> "Google Gemini"
}
