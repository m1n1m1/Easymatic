package com.example.ottomatic.feature.smarthome.hue

import androidx.compose.ui.res.stringResource
import com.example.ottomatic.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ottomatic.data.hue.DiscoveredBridge
import com.example.ottomatic.feature.grapheditor.EditorColors

private val ROW_SHAPE = RoundedCornerShape(16.dp)

/**
 * Stage one of pairing: which bridge?
 *
 * The typed-address field is **always present and never behind a disclosure**, and
 * that is `@WifiNetwork`'s argument in its second form: a chooser can only offer
 * what is reachable right now, and mDNS is blocked by AP isolation, by guest VLANs,
 * by a good number of mesh routers, and by any network that does not reflect
 * multicast between the band the phone is on and the one the bridge is on. Those are
 * exactly the networks that are hardest to debug, so a discovery-only screen would
 * be unusable precisely where help is most needed.
 */
@Composable
fun HueBridgeList(
    bridges: List<DiscoveredBridge>,
    typedHost: String,
    error: String,
    onTypedHostChange: (String) -> Unit,
    onChoose: (host: String, bridgeId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (bridges.isEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(vertical = 8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.smarthome_looking_for_bridges_on_your),
                    style = MaterialTheme.typography.bodyMedium,
                    color = EditorColors.textSecondary,
                )
            }
        }

        bridges.forEach { bridge ->
            BridgeRow(bridge = bridge, onClick = { onChoose(bridge.host, bridge.bridgeId) })
        }

        Text(
            text = stringResource(R.string.smarthome_or_type_the_bridge_s),
            style = MaterialTheme.typography.bodySmall,
            color = EditorColors.textSecondary,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = typedHost,
                onValueChange = onTypedHostChange,
                placeholder = { Text("192.168.1.42") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { onChoose(typedHost.trim(), "") },
                enabled = typedHost.isNotBlank(),
            ) {
                Text(stringResource(R.string.smarthome_connect))
            }
        }

        if (error.isNotBlank()) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = EditorColors.errorAccent,
            )
        }
    }
}

@Composable
private fun BridgeRow(bridge: DiscoveredBridge, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(EditorColors.nodeBackground)
            .border(1.dp, EditorColors.nodeBorder, ROW_SHAPE)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = bridge.host,
                style = MaterialTheme.typography.bodyLarge,
                color = EditorColors.textPrimary,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = listOf(bridge.modelId, bridge.bridgeId).filter { it.isNotBlank() }
                    .joinToString(stringResource(R.string.hue_text))
                    .ifBlank { stringResource(R.string.hue_hue_bridge) },
                style = MaterialTheme.typography.bodySmall,
                color = EditorColors.textSecondary,
            )
        }
    }
}
