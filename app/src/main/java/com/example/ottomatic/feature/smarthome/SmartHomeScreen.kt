package com.example.ottomatic.feature.smarthome

import androidx.compose.ui.res.stringResource
import com.example.ottomatic.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ottomatic.feature.grapheditor.EditorColors
import com.example.ottomatic.domain.model.SmartHomeKind
import com.example.ottomatic.feature.smarthome.homeassistant.HaSetupOverlay
import com.example.ottomatic.feature.smarthome.hue.HuePairingOverlay
import com.example.ottomatic.feature.smarthome.mqtt.MqttSetupOverlay

/**
 * The standalone smart-home hub library, reached from the workflow list's overflow.
 *
 * The same argument as the Geofences, Tags and Mail accounts screens: a library
 * shared across macros needs somewhere to be audited and pruned that is not inside
 * one node's config form. It carries two jobs none of those has — a bridge's
 * certificate can be rotated by a firmware update, and the list of lights on it
 * changes whenever a bulb is added — and both are things that make every macro using
 * the hub stop at once, with nothing anywhere else saying why.
 *
 * **Generic from the first version**, deliberately — and the bet paid: the screen, the
 * storage and the sealed credential were written once, so Home Assistant cost a row in
 * [AddHubSheet] and a setup flow rather than a parallel copy of all of this. Nothing in
 * this file needed editing for it.
 */
@Composable
fun SmartHomeScreen(
    viewModel: SmartHomeViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()

    // Fired and forgotten, and its failures swallowed: a hub that is switched off
    // right now should leave yesterday's lights on screen rather than an empty list.
    LaunchedEffect(Unit) { viewModel.refreshAll() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EditorColors.canvasBackground),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(color = EditorColors.chrome) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(60.dp)
                        .padding(start = 6.dp, end = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.smarthome_back),
                            tint = EditorColors.textPrimary,
                        )
                    }
                    Text(
                        text = stringResource(R.string.smarthome_smart_home),
                        color = EditorColors.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }

            SmartHomeHubList(
                hubs = state.hubs,
                onSelect = { hub -> viewModel.openDetail(hub.id) },
                onAdd = viewModel::openKindChooser,
                needsPairing = viewModel::needsPairing,
                modifier = Modifier.navigationBarsPadding(),
            )
        }
    }

    SmartHomeOverlays(state, viewModel)
}

/**
 * The three things that stack over any surface showing hubs — the library screen and
 * both pickers — so that adding a bridge works identically from all of them.
 */
@Composable
fun SmartHomeOverlays(
    state: SmartHomeUiState,
    viewModel: SmartHomeViewModel,
) {
    state.hubs.firstOrNull { it.id == state.detailId }?.let { hub ->
        HubDetailOverlay(hub = hub, state = state, viewModel = viewModel)
    }

    if (state.choosingKind) {
        AddHubSheet(
            onChoose = viewModel::startPairing,
            onDismiss = viewModel::closeKindChooser,
        )
    }

    // The one place a vendor's setup flow is chosen. The two have nothing in common
    // beyond a name field — see AddHubSheet's KDoc — so this is a branch rather than
    // one overlay with conditional sections.
    state.pairing?.let { pairing ->
        when (pairing.kind) {
            SmartHomeKind.HUE -> HuePairingOverlay(pairing = pairing, viewModel = viewModel)
            SmartHomeKind.HOME_ASSISTANT -> HaSetupOverlay(pairing = pairing, viewModel = viewModel)
            SmartHomeKind.MQTT -> MqttSetupOverlay(pairing = pairing, viewModel = viewModel)
        }
    }
}
