package com.example.ottomatic.feature.smarthome.mqtt

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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.ottomatic.R
import com.example.ottomatic.domain.model.MqttAddress
import com.example.ottomatic.feature.grapheditor.EditorColors
import com.example.ottomatic.feature.grapheditor.EditorOverlay
import com.example.ottomatic.feature.smarthome.PairingStage
import com.example.ottomatic.feature.smarthome.PairingState
import com.example.ottomatic.feature.smarthome.SmartHomeViewModel

/**
 * Connecting an MQTT broker: an address, an optional login, and a name.
 *
 * **The third setup flow, and the plainest of the three** — which is the receipt for
 * `AddHubSheet`'s claim that these have nothing in common beyond a name field. Hue's is a
 * countdown against a button on a physical bridge. Home Assistant's teaches, because a
 * long-lived token is minted five clicks deep in a page most people have never opened.
 * This one is four boxes, because a broker's address and login are things the person
 * running the broker already chose and already knows.
 *
 * **No discovery**, deliberately, and it is the one absence here worth defending. Both
 * other vendors browse mDNS; MQTT has no service type worth browsing for. `_mqtt._tcp` is
 * registered but almost nothing publishes it — Mosquitto does not advertise by default,
 * Home Assistant's add-on does not, and EMQX does not — so a browse would nearly always
 * find nothing, which reads as "no broker here" rather than as "this protocol does not
 * announce itself". A found row that never appears is worse than a field that was always
 * going to be typed.
 *
 * **Anonymous is the ordinary case**, so the login fields carry no asterisk and Connect
 * works with both empty. A broker on a home network usually accepts anyone who can reach
 * the port, which is a fact about how these are deployed rather than an oversight.
 *
 * **Test is not decoration, and it carries more weight here than on either other screen.**
 * A broker gives nothing else to go on: no web interface the user has already logged into,
 * no credential visibly minted. An address typed one digit wrong is indistinguishable from
 * a working setup until a macro silently fails to publish at three in the morning.
 */
@Composable
fun MqttSetupOverlay(
    pairing: PairingState,
    viewModel: SmartHomeViewModel,
) {
    EditorOverlay(
        title = stringResource(
            if (pairing.stage == PairingStage.NAMING) {
                R.string.mqtt_name_your_broker
            } else {
                R.string.mqtt_connect_a_broker
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
    Text(
        text = stringResource(R.string.mqtt_setup_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = EditorColors.textSecondary,
    )

    OutlinedTextField(
        value = pairing.typedHost,
        onValueChange = viewModel::typedHostChanged,
        label = { Text(stringResource(R.string.mqtt_address)) },
        placeholder = { Text(MQTT_EXAMPLE) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    // Warns rather than refuses, on AiBaseUrl.isCleartext's rule — with one difference
    // that makes the warning milder here: a broker on a home network is plaintext on 1883
    // by default, so this is the normal configuration rather than a corner somebody has
    // ended up in. Only the user knows whether the far end is their own cupboard.
    if (MqttAddress.isCleartext(pairing.typedHost)) {
        Text(
            text = stringResource(R.string.mqtt_cleartext_warning),
            style = MaterialTheme.typography.bodySmall,
            color = EditorColors.textSecondary,
        )
    }

    Text(
        text = stringResource(R.string.mqtt_login_optional),
        style = MaterialTheme.typography.bodySmall,
        color = EditorColors.textSecondary,
    )
    OutlinedTextField(
        value = pairing.username,
        onValueChange = viewModel::usernameChanged,
        label = { Text(stringResource(R.string.mqtt_username)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = pairing.token,
        onValueChange = viewModel::tokenChanged,
        label = { Text(stringResource(R.string.mqtt_password)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = viewModel::connectMqtt,
            enabled = !pairing.working && MqttAddress.parse(pairing.typedHost) != null,
        ) { Text(stringResource(R.string.smarthome_connect)) }
        TextButton(
            onClick = viewModel::testMqtt,
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

@Composable
private fun NamingStage(pairing: PairingState, viewModel: SmartHomeViewModel) {
    Text(
        text = stringResource(R.string.mqtt_connected_this_is_the_name),
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

/**
 * What a broker address looks like.
 *
 * A bare IP rather than a full `mqtt://` URL, because that is both the commonest answer
 * and the one that demonstrates the scheme is optional — a placeholder showing a scheme
 * would teach that one is required.
 */
private const val MQTT_EXAMPLE = "192.168.1.5"
