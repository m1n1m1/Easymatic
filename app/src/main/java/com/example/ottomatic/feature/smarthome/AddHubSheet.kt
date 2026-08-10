package com.example.ottomatic.feature.smarthome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ottomatic.domain.model.SmartHomeKind
import com.example.ottomatic.feature.grapheditor.EditorColors
import com.example.ottomatic.feature.grapheditor.EditorOverlay

private val ROW_SHAPE = RoundedCornerShape(16.dp)

/**
 * "What kind of hub?"
 *
 * One row today, and that is not a wasted screen — it is **the seam**. This is the
 * only file a second integration touches on the way in: a new
 * [SmartHomeKind] constant, a row here, and its own pairing flow. Everything else —
 * the list, the storage, the sealed credential, the detail sheet, the two pickers,
 * the three nodes — is already written and already generic.
 *
 * A one-question overlay rather than a menu on the "+" row, because the answer
 * decides which pairing flow opens, and those flows have nothing in common: pressing
 * a button on a bridge, scanning a QR code and signing in to an account are three
 * different screens, not three fields.
 */
@Composable
fun AddHubSheet(
    onChoose: (SmartHomeKind) -> Unit,
    onDismiss: () -> Unit,
) {
    EditorOverlay(title = "Add a hub", onClose = onDismiss) { dismiss ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "What are you connecting to?",
                style = MaterialTheme.typography.bodyMedium,
                color = EditorColors.textSecondary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            KindRow(
                title = "Philips Hue",
                subtitle = "A bridge on your Wi-Fi",
                onClick = {
                    onChoose(SmartHomeKind.HUE)
                    dismiss()
                },
            )
        }
    }
}

@Composable
private fun KindRow(title: String, subtitle: String, onClick: () -> Unit) {
    val accent = EditorColors.actionAccent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(EditorColors.nodeBackground)
            .border(1.dp, EditorColors.nodeBorder, ROW_SHAPE)
            .clickable(onClick = onClick)
            .padding(14.dp),
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
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = EditorColors.textPrimary,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = EditorColors.textSecondary,
            )
        }
    }
}
