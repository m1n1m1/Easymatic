package com.example.ottomatic.feature.smarthome

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Settings
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
import com.example.ottomatic.core.service.SmartHomeTargetKind
import com.example.ottomatic.domain.model.SmartHomeHub
import com.example.ottomatic.feature.grapheditor.EditorColors

private val ROW_SHAPE = RoundedCornerShape(16.dp)

/**
 * The hub library as a list, shared by the standalone Smart home screen and by both
 * pickers' "no hub yet" state.
 *
 * Every row is a hub whatever kind it is, which is the whole reason this screen is
 * not called "Philips Hue": a household with a Hue bridge and something else should
 * see one list of the things it has connected, not one screen per vendor.
 */
@Composable
fun SmartHomeHubList(
    hubs: List<SmartHomeHub>,
    onSelect: (SmartHomeHub) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    needsPairing: (String) -> Boolean = { false },
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "add") { AddHubRow(onClick = onAdd) }
        if (hubs.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = "No hubs yet. Add one to control your lights from a macro. " +
                        "You will need to press the button on the bridge once, to prove you " +
                        "are standing next to it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EditorColors.textSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
                )
            }
        }
        items(hubs, key = { it.id }) { hub ->
            HubRow(
                hub = hub,
                needsPairing = needsPairing(hub.id),
                onClick = { onSelect(hub) },
            )
        }
    }
}

@Composable
private fun AddHubRow(onClick: () -> Unit) {
    val accent = EditorColors.actionAccent
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
            text = "Add a hub",
            style = MaterialTheme.typography.bodyLarge,
            color = accent,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun HubRow(
    hub: SmartHomeHub,
    needsPairing: Boolean,
    onClick: () -> Unit,
) {
    val accent = EditorColors.actionAccent
    // A hub that has lost its key is drawn on the row rather than left for a failed
    // macro to reveal: this is the state a restored phone lands in, and it looks
    // exactly like a working hub until something tries to use it.
    val borderColor = if (needsPairing) EditorColors.errorAccent else EditorColors.nodeBorder
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(EditorColors.nodeBackground)
            .border(1.dp, borderColor, ROW_SHAPE)
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
                imageVector = Icons.Filled.Lightbulb,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = hub.name,
                style = MaterialTheme.typography.bodyLarge,
                color = EditorColors.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (needsPairing) "Pair again" else hub.summary(),
                style = MaterialTheme.typography.bodySmall,
                color = if (needsPairing) EditorColors.errorAccent else EditorColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onClick) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Settings for ${hub.name}",
                tint = EditorColors.textSecondary,
            )
        }
    }
}

/**
 * The address, and what has been read from it.
 *
 * The counts are there because an empty snapshot and a hub that is simply new look
 * identical from the list, and the difference is the whole of "why is my picker
 * empty?".
 */
private fun SmartHomeHub.summary(): String {
    val lights = resourcesOf(SmartHomeTargetKind.LIGHT).size
    val scenes = resourcesOf(SmartHomeTargetKind.SCENE).size
    return if (resources.isEmpty()) host else "$host · $lights lights · $scenes scenes"
}
