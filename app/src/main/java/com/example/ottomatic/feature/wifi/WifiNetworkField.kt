package com.example.ottomatic.feature.wifi

import androidx.compose.ui.res.stringResource
import com.example.ottomatic.R
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow

/**
 * A `@WifiNetwork` field: a network name the user can type, with a button that lists
 * the networks currently in range.
 *
 * Editable, unlike a `@Picker`, and it earns that more plainly than [PhoneNumberField]
 * does: "when I connect to my office Wi-Fi" is configured **at home**, where the
 * office network cannot be scanned. Scanning also needs a location grant, so a
 * read-only field would additionally turn a denied permission into a field that can
 * never be set at all, rather than one that is merely unassisted. The scan is a
 * suggestion; the answer set is every network that exists.
 *
 * Simpler than [PhoneNumberField] in the one way that matters: the stored value *is*
 * the SSID, not a spec that resolves to something else, so there is no cached display
 * name and no read-only mode after a pick. Typed text and picked text are the same
 * kind of thing, which is why the ✕ only has to mean "any network".
 */
@Composable
fun WifiNetworkField(
    value: String,
    onValueChange: (String) -> Unit,
    labelSlot: @Composable () -> Unit,
    colors: TextFieldColors,
) {
    var picking by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = labelSlot,
        colors = colors,
        // Blank is a real answer here rather than an unconfigured field, so the
        // placeholder states it instead of saying "none selected".
        placeholder = { Text(text =
            stringResource(R.string.wifi_any_network), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = {
            Row {
                if (value.isNotBlank()) {
                    IconButton(onClick = { onValueChange("") }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.wifi_clear_the_network_so_this),
                        )
                    }
                }
                IconButton(onClick = { picking = true }) {
                    Icon(imageVector = Icons.Filled.Wifi, contentDescription =
                        stringResource(R.string.wifi_choose_a_network_in_range))
                }
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    if (picking) {
        WifiNetworkPickerOverlay(
            selected = value.takeIf { it.isNotBlank() },
            onPick = {
                onValueChange(it)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}
