package io.github.m1n1m1.easymatic.feature.smarthome

import androidx.compose.ui.res.stringResource
import io.github.m1n1m1.easymatic.R
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
import io.github.m1n1m1.easymatic.core.service.SmartHomeTargetKind
import io.github.m1n1m1.easymatic.domain.model.HubAuthMode
import io.github.m1n1m1.easymatic.domain.model.SmartHomeHub
import io.github.m1n1m1.easymatic.domain.model.SmartHomeKind
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorOverlay

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
        title = stringResource(R.string.smarthome_hub),
        onClose = viewModel::closeDetail,
        action = {
            IconButton(onClick = { viewModel.delete(hub.id) }) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.smarthome_remove_hub),
                    tint = EditorColors.textSecondary,
                )
            }
            TextButton(onClick = viewModel::saveDetail) { Text(stringResource(R.string.smarthome_save)) }
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
                label = { Text(stringResource(R.string.smarthome_name)) },
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
                    // Two different acts behind one button. Refreshing a bridge or an
                    // instance *reads* what is there; refreshing a broker **listens**,
                    // because there is nothing to read — so the label says so, or the
                    // empty result would read as a failure rather than as a quiet house.
                    Text(
                        stringResource(
                            if (hub.kind == SmartHomeKind.MQTT) {
                                R.string.mqtt_listen_for_topics
                            } else {
                                R.string.smarthome_refresh_lights_and_scenes
                            },
                        ),
                    )
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

            // Hue only, and not because Home Assistant's certificate matters less: it
            // is that there is no pin to mismatch. A Hue bridge presents a certificate
            // signed by a root no device trusts, so the app pins one by fingerprint;
            // Home Assistant is reached over plain HTTP on the LAN or over a
            // certificate the platform verifies for itself, and neither has anything
            // for this card to compare.
            if (hub.kind == SmartHomeKind.HUE && state.presentedCertificate.isNotBlank()) {
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
        InfoLine(stringResource(R.string.smarthome_address), hub.host)
        // A broker's card stops here plus two lines of its own. The light counts below
        // would all be zero on one, which reads as a hub that failed to read rather than
        // as one that has no lights to have — see SmartHomeKind.MQTT.
        if (hub.kind == SmartHomeKind.MQTT) {
            InfoLine(
                stringResource(R.string.mqtt_username),
                hub.username.ifBlank { stringResource(R.string.mqtt_anonymous) },
            )
            InfoLine(stringResource(R.string.mqtt_topics_heard), hub.topics.size.toString())
            return@Column
        }
        if (hub.hardwareId.isNotBlank()) InfoLine(stringResource(R.string.smarthome_bridge_id), hub.hardwareId)
        // How the credential was obtained, and therefore what has to happen when it
        // stops working: a pasted token is replaced by hand, a signed-in one renews
        // itself. A Hue key has neither and the row would say nothing.
        if (hub.authMode != HubAuthMode.NONE) {
            InfoLine(
                stringResource(R.string.ha_authentication),
                stringResource(
                    if (hub.authMode == HubAuthMode.OAUTH) R.string.ha_auth_oauth else R.string.ha_auth_token,
                ),
            )
        }
        InfoLine(stringResource(R.string.smarthome_lights), hub.resourcesOf(SmartHomeTargetKind.LIGHT).size.toString())
        InfoLine(
            stringResource(R.string.smarthome_rooms_and_zones),
            hub.resourcesOf(SmartHomeTargetKind.GROUP).size.toString(),
        )
        InfoLine(
            stringResource(R.string.smarthome_scenes),
            hub.resourcesOf(SmartHomeTargetKind.SCENE).size.toString(),
        )
        // Every entity, not just the light-shaped ones — which on a real install is a
        // far bigger number, and the one that says whether the snapshot worked.
        if (hub.entities.isNotEmpty()) {
            InfoLine(stringResource(R.string.ha_all_entities), hub.entities.size.toString())
        }
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
            text = stringResource(R.string.smarthome_this_address_is_presenting_a),
            style = MaterialTheme.typography.bodyMedium,
            color = EditorColors.textPrimary,
        )
        Text(
            text = stringResource(R.string.smarthome_only_trust_it_if_you),
            style = MaterialTheme.typography.bodySmall,
            color = EditorColors.textSecondary,
        )
        InfoLine(stringResource(R.string.smarthome_trusted), pinned.grouped())
        InfoLine(stringResource(R.string.smarthome_presented), presented.grouped())
        TextButton(onClick = onTrust) { Text(stringResource(R.string.smarthome_trust_new_certificate)) }
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
