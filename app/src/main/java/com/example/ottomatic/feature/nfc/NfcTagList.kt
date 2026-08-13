package com.example.ottomatic.feature.nfc

import androidx.compose.ui.res.stringResource
import com.example.ottomatic.R
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Nfc
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
import com.example.ottomatic.domain.model.NfcTag
import com.example.ottomatic.domain.model.NfcTagId
import com.example.ottomatic.feature.grapheditor.EditorColors

private val ROW_SHAPE = RoundedCornerShape(16.dp)

/**
 * The tag library as a list, shared by the standalone Tags screen and the picker a
 * `trigger.nfc` opens.
 *
 * Modelled on `GeofencePlaceList`, with one row it does not have: [showAnyTag] adds
 * an "Any tag" entry, because blank is a real answer for a tag filter and is not one
 * for a place. The picker shows it; the library screen, where there is nothing to
 * pick, does not.
 */
@Composable
fun NfcTagList(
    tags: List<NfcTag>,
    onSelect: (NfcTag) -> Unit,
    onEdit: (NfcTag) -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
    selectedUid: String? = null,
    showAnyTag: Boolean = false,
    onPickAny: () -> Unit = {},
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "scan") { ScanTagRow(onClick = onScan) }
        if (showAnyTag) {
            item(key = "any") { AnyTagRow(selected = selectedUid == null, onClick = onPickAny) }
        }
        if (tags.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.nfc_no_tags_yet_scan_one),
                    style = MaterialTheme.typography.bodyMedium,
                    color = EditorColors.textSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
                )
            }
        }
        items(tags, key = { it.uid }) { tag ->
            TagRow(
                tag = tag,
                selected = tag.uid == selectedUid,
                onClick = { onSelect(tag) },
                onEdit = { onEdit(tag) },
            )
        }
    }
}

@Composable
private fun ScanTagRow(onClick: () -> Unit) {
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
        Icon(Icons.Filled.Nfc, contentDescription = null, tint = accent)
        Text(
            text = stringResource(R.string.nfc_scan_a_new_tag),
            style = MaterialTheme.typography.bodyLarge,
            color = accent,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun AnyTagRow(selected: Boolean, onClick: () -> Unit) {
    val accent = EditorColors.triggerAccent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(accent.copy(alpha = 0.08f))
            .border(if (selected) 2.dp else 1.dp, accent.copy(alpha = 0.35f), ROW_SHAPE)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Close, contentDescription = null, tint = accent)
        Text(
            text = stringResource(R.string.nfc_any_tag),
            style = MaterialTheme.typography.bodyLarge,
            color = accent,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun TagRow(
    tag: NfcTag,
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
                imageVector = Icons.Filled.Nfc,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tag.name,
                style = MaterialTheme.typography.bodyLarge,
                color = EditorColors.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The id, because two tags called "Desk" and "Desk 2" are told apart by
            // nothing else, and because it is what a macro actually stores.
            Text(
                text = NfcTagId.display(tag.uid),
                style = MaterialTheme.typography.bodySmall,
                color = EditorColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = stringResource(R.string.nfc_rename_named, tag.name),
                tint = EditorColors.textSecondary,
            )
        }
    }
}
