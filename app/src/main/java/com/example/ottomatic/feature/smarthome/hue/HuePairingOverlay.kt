package com.example.ottomatic.feature.smarthome.hue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ottomatic.data.hue.DiscoveredBridge
import com.example.ottomatic.data.hue.HueDiscovery
import com.example.ottomatic.feature.grapheditor.EditorColors
import com.example.ottomatic.feature.grapheditor.EditorOverlay
import com.example.ottomatic.feature.smarthome.PairingStage
import com.example.ottomatic.feature.smarthome.PairingState
import com.example.ottomatic.feature.smarthome.SmartHomeViewModel

/**
 * Pairing a Hue bridge, in three stages inside one overlay.
 *
 * The stages are separate screens rather than one long form because they are three
 * different kinds of act: choosing a thing, doing something physical in another
 * room, and typing a name. Only the second has a deadline, and a countdown beside a
 * half-filled form would be a form nobody finishes.
 *
 * The hub exists from the moment stage two succeeds — see
 * [com.example.ottomatic.data.hue.SmartHomeSetup.pair]. Stage three renames it, so
 * walking away leaves a working bridge rather than a lost key.
 */
@Composable
fun HuePairingOverlay(
    pairing: PairingState,
    viewModel: SmartHomeViewModel,
) {
    EditorOverlay(
        title = when (pairing.stage) {
            PairingStage.CHOOSING -> "Find your bridge"
            PairingStage.LINKING -> "Press the link button"
            PairingStage.NAMING -> "Name your bridge"
        },
        onClose = viewModel::cancelPairing,
        action = {
            if (pairing.stage == PairingStage.NAMING) {
                TextButton(onClick = viewModel::finishPairing) { Text("Done") }
            }
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
            when (pairing.stage) {
                PairingStage.CHOOSING -> ChoosingStage(pairing, viewModel)
                PairingStage.LINKING -> LinkingStage(pairing)
                PairingStage.NAMING -> NamingStage(pairing, viewModel)
            }
        }
    }
}

@Composable
private fun ChoosingStage(pairing: PairingState, viewModel: SmartHomeViewModel) {
    val context = LocalContext.current
    // Collected here rather than in the ViewModel so the browse — and the multicast
    // lock it holds — lives exactly as long as this screen is on it.
    val bridges by produceState(initialValue = emptyList<DiscoveredBridge>(), context) {
        val found = mutableListOf<DiscoveredBridge>()
        HueDiscovery.bridges(context).collect { bridge ->
            found += bridge
            value = found.toList()
        }
    }

    HueBridgeList(
        bridges = bridges,
        typedHost = pairing.typedHost,
        error = pairing.error,
        onTypedHostChange = viewModel::typedHostChanged,
        onChoose = { host, bridgeId -> viewModel.linkTo(host, bridgeId) },
    )
}

@Composable
private fun LinkingStage(pairing: PairingState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            Text(
                text = "${pairing.secondsLeft}s",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = EditorColors.textPrimary,
            )
        }
        Text(
            text = "Press the round button on top of your bridge.",
            style = MaterialTheme.typography.bodyLarge,
            color = EditorColors.textPrimary,
        )
        Text(
            text = "It is the large button in the middle of the disc, between the three " +
                "small lights. Connecting to ${pairing.host}.",
            style = MaterialTheme.typography.bodySmall,
            color = EditorColors.textSecondary,
        )
    }
}

@Composable
private fun NamingStage(pairing: PairingState, viewModel: SmartHomeViewModel) {
    Text(
        text = "Paired. This is the name your macros will show.",
        style = MaterialTheme.typography.bodyMedium,
        color = EditorColors.textSecondary,
    )
    OutlinedTextField(
        value = pairing.name,
        onValueChange = viewModel::pairedNameChanged,
        label = { Text("Name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
