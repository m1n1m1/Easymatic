package com.example.ottomatic.feature.smarthome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.ottomatic.core.service.SmartHomeTargetKind
import com.example.ottomatic.domain.model.SmartHomeHub
import com.example.ottomatic.feature.grapheditor.EditorColors
import com.example.ottomatic.feature.grapheditor.EditorOverlay

private val CARD_SHAPE = RoundedCornerShape(14.dp)

/**
 * One hub: what it is, what has been read from it, and the two things that go wrong.
 *
 * **Refresh** exists because the resource snapshot is deliberately never read on an
 * execution path — a node PUTs to an id it was handed — so a bulb added in the
 * vendor's app appears here only when somebody asks.
 *
 * **Trust new certificate** is what makes pinning survivable. A bridge's certificate
 * can be rotated by a firmware update, and a pure trust-on-first-use pin would turn
 * that into "everything stopped working" with no route out. It shows *both*
 * fingerprints, because the only way this can be answered honestly is by comparing
 * them, and it appears only when they actually differ — two identical fingerprints
 * side by side is noise.
 */
@Composable
fun HubDetailOverlay(
    hub: SmartHomeHub,
    state: SmartHomeUiState,
    viewModel: SmartHomeViewModel,
) {
    EditorOverlay(
        title = "Hub",
        onClose = viewModel::closeDetail,
        action = {
            IconButton(onClick = { viewModel.delete(hub.id) }) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Remove hub",
                    tint = EditorColors.textSecondary,
                )
            }
            TextButton(onClick = viewModel::saveDetail) { Text("Save") }
        },
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = state.detailName,
                onValueChange = viewModel::detailNameChanged,
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            InfoCard(hub)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(onClick = { viewModel.refresh(hub.id) }, enabled = !state.busy) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("  Refresh lights and scenes")
                }
                if (state.busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }

            if (state.message.isNotBlank()) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = EditorColors.textSecondary,
                )
            }

            if (state.presentedCertificate.isNotBlank()) {
                CertificateMismatch(
                    pinned = hub.certSha256,
                    presented = state.presentedCertificate,
                    onTrust = viewModel::trustPresentedCertificate,
                )
            }
        }
    }
}

@Composable
private fun InfoCard(hub: SmartHomeHub) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CARD_SHAPE)
            .background(EditorColors.nodeBackground)
            .border(1.dp, EditorColors.nodeBorder, CARD_SHAPE)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        InfoLine("Address", hub.host)
        if (hub.hardwareId.isNotBlank()) InfoLine("Bridge id", hub.hardwareId)
        InfoLine("Lights", hub.resourcesOf(SmartHomeTargetKind.LIGHT).size.toString())
        InfoLine("Rooms and zones", hub.resourcesOf(SmartHomeTargetKind.GROUP).size.toString())
        InfoLine("Scenes", hub.resourcesOf(SmartHomeTargetKind.SCENE).size.toString())
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = EditorColors.textSecondary)
        Text(value, style = MaterialTheme.typography.bodySmall, color = EditorColors.textPrimary)
    }
}

@Composable
private fun CertificateMismatch(pinned: String, presented: String, onTrust: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CARD_SHAPE)
            .background(EditorColors.errorAccent.copy(alpha = 0.10f))
            .border(1.dp, EditorColors.errorAccent.copy(alpha = 0.45f), CARD_SHAPE)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "This address is presenting a different certificate",
            style = MaterialTheme.typography.bodyMedium,
            color = EditorColors.textPrimary,
        )
        Text(
            text = "Only trust it if you reset or replaced your bridge. If you did not, " +
                "something else on your network is answering as it.",
            style = MaterialTheme.typography.bodySmall,
            color = EditorColors.textSecondary,
        )
        InfoLine("Trusted", pinned.grouped())
        InfoLine("Presented", presented.grouped())
        TextButton(onClick = onTrust) { Text("Trust new certificate") }
    }
}

/**
 * A fingerprint in four-character groups.
 *
 * Sixty-four unbroken hex characters cannot be compared by eye, and comparing them
 * by eye is the entire job this row exists for.
 */
private fun String.grouped(): String = chunked(GROUP_SIZE).joinToString(" ")

private const val GROUP_SIZE = 4
