package com.example.ottomatic.feature.smarthome.homeassistant

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.example.ottomatic.R
import com.example.ottomatic.data.homeassistant.DiscoveredInstance
import com.example.ottomatic.data.homeassistant.HaDiscovery
import com.example.ottomatic.domain.model.HaBaseUrl
import com.example.ottomatic.feature.grapheditor.EditorColors
import com.example.ottomatic.feature.grapheditor.EditorOverlay
import com.example.ottomatic.feature.smarthome.PairingStage
import com.example.ottomatic.feature.smarthome.PairingState
import com.example.ottomatic.feature.smarthome.SmartHomeViewModel

private val ROW_SHAPE = RoundedCornerShape(14.dp)

/**
 * Connecting a Home Assistant instance: an address, a token, and a name.
 *
 * **Two stages where Hue has three**, and the missing one is the whole difference
 * between the vendors: there is no window to count down and nothing is minted, so
 * connecting either works or says why. Putting a countdown here would be a deadline
 * over a form with nothing to wait for.
 *
 * **The screen teaches**, which the Hue one does not have to and
 * `AiConnectionsScreen` already established as the rule for this shape of credential.
 * A Hue bridge has a button on it that anybody can find; a long-lived access token is
 * minted five clicks deep in a page most people have never opened, and "paste your
 * token" silently excludes everybody who does not already know what one is. So the
 * steps are numbered, the profile page **opens on a tap** rather than being printed as
 * a URL to transcribe, and a **Paste** button sits beside the field because the token
 * arrives by clipboard every single time.
 *
 * **Test is not decoration.** Without it the first proof a token works is a macro
 * failing quietly at three in the morning, which is exactly the failure this
 * integration exists not to have.
 */
@Composable
fun HaSetupOverlay(
    pairing: PairingState,
    viewModel: SmartHomeViewModel,
) {
    EditorOverlay(
        title = stringResource(
            if (pairing.stage == PairingStage.NAMING) {
                R.string.ha_name_your_hub
            } else {
                R.string.ha_connect_home_assistant
            },
        ),
        onClose = viewModel::cancelPairing,
        action = {
            if (pairing.stage == PairingStage.NAMING) {
                TextButton(onClick = viewModel::finishPairing) { Text(stringResource(R.string.smarthome_done)) }
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
            if (pairing.stage == PairingStage.NAMING) {
                NamingStage(pairing, viewModel)
            } else {
                ConnectStage(pairing, viewModel)
            }
        }
    }
}

@Composable
private fun ConnectStage(pairing: PairingState, viewModel: SmartHomeViewModel) {
    val context = LocalContext.current
    // Collected here rather than in the ViewModel so the browse — and the multicast
    // lock it holds — lives exactly as long as this screen is on it.
    val instances by produceState(initialValue = emptyList<DiscoveredInstance>(), context) {
        val found = mutableListOf<DiscoveredInstance>()
        HaDiscovery.instances(context).collect { instance ->
            found += instance
            value = found.toList()
        }
    }

    Text(
        text = stringResource(R.string.ha_looking_for_instances),
        style = MaterialTheme.typography.bodyMedium,
        color = EditorColors.textSecondary,
    )
    instances.forEach { instance ->
        InstanceRow(instance) { viewModel.typedHostChanged(instance.baseUrl) }
    }

    OutlinedTextField(
        value = pairing.typedHost,
        onValueChange = viewModel::typedHostChanged,
        label = { Text(stringResource(R.string.ha_address)) },
        placeholder = { Text(HaBaseUrl.EXAMPLE) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    // Only the user knows whether the far end is their living room or the far side of
    // the internet, so this warns rather than refuses — AiBaseUrl.isCleartext's rule.
    if (HaBaseUrl.isCleartext(pairing.typedHost)) {
        Text(
            text = stringResource(R.string.ha_cleartext_warning),
            style = MaterialTheme.typography.bodySmall,
            color = EditorColors.textSecondary,
        )
    }

    TokenSteps(pairing, viewModel)

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = viewModel::connectHomeAssistant,
            enabled = !pairing.working,
        ) { Text(stringResource(R.string.smarthome_connect)) }
        TextButton(
            onClick = viewModel::testHomeAssistant,
            enabled = !pairing.working,
        ) { Text(stringResource(R.string.ha_test)) }
        if (pairing.working) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
    }

    if (pairing.tested.isNotBlank()) {
        Text(
            text = pairing.tested,
            style = MaterialTheme.typography.bodySmall,
            color = EditorColors.textSecondary,
        )
    }
    if (pairing.error.isNotBlank()) {
        Text(
            text = pairing.error,
            style = MaterialTheme.typography.bodySmall,
            color = EditorColors.errorAccent,
        )
    }
}

/**
 * The numbered steps, and the two buttons that mean nobody has to transcribe anything.
 *
 * A useful instruction names the button somebody is hunting for, which is why these
 * spell out the path through the profile page rather than saying "create a token".
 */
@Composable
private fun TokenSteps(pairing: PairingState, viewModel: SmartHomeViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    Text(
        text = stringResource(R.string.ha_token_steps),
        style = MaterialTheme.typography.bodySmall,
        color = EditorColors.textSecondary,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            // Deep-links straight to the security tab of the profile page, which is
            // where the token is minted — the whole point of opening it for them.
            onClick = {
                HaBaseUrl.parse(pairing.typedHost)?.let { base ->
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, "$base$PROFILE_PATH".toUri())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            },
            enabled = HaBaseUrl.parse(pairing.typedHost) != null,
        ) { Text(stringResource(R.string.ha_open_profile)) }
        TextButton(
            onClick = { clipboard.getText()?.text?.let(viewModel::tokenChanged) },
        ) { Text(stringResource(R.string.ha_paste)) }
    }
    OutlinedTextField(
        value = pairing.token,
        onValueChange = viewModel::tokenChanged,
        label = { Text(stringResource(R.string.ha_access_token)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun InstanceRow(instance: DiscoveredInstance, onClick: () -> Unit) {
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
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = instance.name.ifBlank { instance.baseUrl },
                style = MaterialTheme.typography.bodyLarge,
                color = EditorColors.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = instance.baseUrl,
                style = MaterialTheme.typography.bodySmall,
                color = EditorColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NamingStage(pairing: PairingState, viewModel: SmartHomeViewModel) {
    Text(
        text = stringResource(R.string.ha_connected_this_is_the_name),
        style = MaterialTheme.typography.bodyMedium,
        color = EditorColors.textSecondary,
    )
    OutlinedTextField(
        value = pairing.name,
        onValueChange = viewModel::pairedNameChanged,
        label = { Text(stringResource(R.string.smarthome_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Where a long-lived access token is minted. */
private const val PROFILE_PATH = "/profile/security"
