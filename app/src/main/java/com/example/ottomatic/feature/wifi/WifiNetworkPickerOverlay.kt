package com.example.ottomatic.feature.wifi

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NetworkWifi
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SignalWifi4Bar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.data.wifi.WifiNetworkInRange
import com.example.ottomatic.data.wifi.WifiNetworks
import com.example.ottomatic.feature.grapheditor.EditorColors
import com.example.ottomatic.feature.grapheditor.EditorOverlay
import com.example.ottomatic.feature.permissions.rememberPermissionState

private val ROW_SHAPE = RoundedCornerShape(16.dp)

/**
 * The chooser behind a `@WifiNetwork` field's button: the networks the device can
 * currently see.
 *
 * Follows [com.example.ottomatic.feature.apps.AppPickerOverlay]'s deferred-pick
 * idiom — the tap records the choice and closes, and [onPick] runs from `onClose`
 * once the exit animation has finished — and its row styling, so the two choosers
 * read as one thing.
 *
 * It differs in being the only chooser in the app that can be **refused**. Listing
 * networks needs `ACCESS_FINE_LOCATION`, and there is no transient grant to fall back
 * on the way `ACTION_PICK` gives the contact picker one. That is survivable only
 * because the field behind it stays typeable: this can say what is missing and offer
 * the prompt, and closing it empty-handed still leaves a usable field. A read-only
 * picker in the same position would be a dead end.
 */
@Composable
fun WifiNetworkPickerOverlay(
    selected: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var picked by remember { mutableStateOf<String?>(null) }
    val permissionState = rememberPermissionState(listOf(Permissions.ACCESS_FINE_LOCATION))
    val granted = permissionState.allGranted

    // Bumped by Rescan, and keyed into the load below. `startScan` only *asks* — the
    // results land in the platform's cache a moment later — so the reload is what
    // actually refreshes the list, and re-reading a moment too early costs nothing
    // but a repeat of what is already shown.
    var refresh by remember { mutableIntStateOf(0) }
    var networks by remember { mutableStateOf<List<WifiNetworkInRange>?>(null) }

    LaunchedEffect(granted, refresh) {
        if (!granted) {
            networks = emptyList()
            return@LaunchedEffect
        }
        networks = null
        networks = WifiNetworks.inRange(context)
    }

    EditorOverlay(
        title = "Choose a network",
        onClose = { picked?.let(onPick) ?: onDismiss() },
    ) { dismiss ->
        Column(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
            if (!granted) {
                LocationNotice(onGrant = permissionState::request)
                return@Column
            }
            RescanRow(
                onRescan = {
                    WifiNetworks.rescan(context)
                    refresh++
                },
            )
            val loaded = networks
            if (loaded == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = EditorColors.triggerAccent)
                }
                return@Column
            }
            NetworkList(
                networks = loaded,
                selected = selected,
                onPick = {
                    picked = it
                    dismiss()
                },
            )
        }
    }
}

/**
 * Why the list is empty, and the one tap that fixes it.
 *
 * Says *why* location is being asked for, because "a Wi-Fi picker wants your
 * location" reads as overreach until you know that the network name is itself a
 * statement about where you are — and a prompt that looks like overreach is one that
 * gets denied.
 */
@Composable
private fun LocationNotice(onGrant: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Ottomatic needs Location to list Wi-Fi networks",
            style = MaterialTheme.typography.titleMedium,
            color = EditorColors.textPrimary,
        )
        Text(
            text = "Android treats the name of a network as a clue to where you are, so it will not " +
                "name one without this. You can close this and type the network's name instead — " +
                "the trigger still needs the permission to match it.",
            style = MaterialTheme.typography.bodyMedium,
            color = EditorColors.textSecondary,
        )
        Button(onClick = onGrant) { Text("Grant") }
    }
}

@Composable
private fun RescanRow(onRescan: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onRescan) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                tint = EditorColors.triggerAccent,
            )
            Text(text = "Rescan", color = EditorColors.triggerAccent)
        }
    }
}

@Composable
private fun NetworkList(
    networks: List<WifiNetworkInRange>,
    selected: String?,
    onPick: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "any") { AnyNetworkRow(selected = selected == null, onClick = { onPick("") }) }
        items(networks, key = { it.ssid }) { network ->
            NetworkRow(
                network = network,
                selected = network.ssid == selected,
                onClick = { onPick(network.ssid) },
            )
        }
        if (networks.isEmpty()) {
            item(key = "empty") {
                Text(
                    // Three separate causes, all of which look identical from here —
                    // the platform answers a scan it will not serve with no results
                    // rather than with a reason — so the message names all three.
                    text = "No networks found. Wi-Fi and Location both have to be switched on to " +
                        "scan, and Android limits how often it will look. You can type a network's " +
                        "name instead.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EditorColors.textSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun AnyNetworkRow(selected: Boolean, onClick: () -> Unit) {
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
            text = "Any network",
            style = MaterialTheme.typography.bodyLarge,
            color = accent,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun NetworkRow(network: WifiNetworkInRange, selected: Boolean, onClick: () -> Unit) {
    val accent = EditorColors.triggerAccent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(EditorColors.nodeBackground)
            .border(if (selected) 2.dp else 1.dp, if (selected) accent else EditorColors.nodeBorder, ROW_SHAPE)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (network.connected) Icons.Filled.SignalWifi4Bar else Icons.Filled.NetworkWifi,
            contentDescription = null,
            // Strength as opacity rather than as a number: it only has to say which
            // of these is the strong one, and a dBm figure says nothing to anybody.
            tint = accent.copy(alpha = strengthAlpha(network.signalLevel)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = network.ssid,
                style = MaterialTheme.typography.bodyLarge,
                color = EditorColors.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (network.connected) {
                Text(
                    text = "Connected",
                    style = MaterialTheme.typography.bodySmall,
                    color = EditorColors.textSecondary,
                )
            }
        }
    }
}

private fun strengthAlpha(level: Int): Float =
    MIN_STRENGTH_ALPHA + (1f - MIN_STRENGTH_ALPHA) * level / (WifiNetworks.SIGNAL_LEVELS - 1)

/** The weakest network is still legible, so strength dims the icon rather than hiding it. */
private const val MIN_STRENGTH_ALPHA = 0.4f
