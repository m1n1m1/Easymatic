package io.github.m1n1m1.easymatic.feature.smarthome

import androidx.compose.ui.res.stringResource
import io.github.m1n1m1.easymatic.R
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.m1n1m1.easymatic.domain.model.SmartHomeKind
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorOverlay

private val ROW_SHAPE = RoundedCornerShape(16.dp)

/**
 * "What kind of hub?"
 *
 * Three rows, and the two after the first are the receipt for it having sat here alone.
 * This is **the seam**, and both later vendors cost what it promised on the way in: a
 * [SmartHomeKind] constant, a row here, and their own setup flow. The list, the storage,
 * the sealed credential and the detail sheet were already written and already generic.
 *
 * A one-question overlay rather than a menu on the "+" row, because the answer decides
 * which setup flow opens, and those flows have nothing in common — which the three here
 * now demonstrate rather than merely predict. Hue's is a **countdown**: walk to the
 * bridge, press the button inside a minute, and it mints a key it will never mint
 * again. Home Assistant's **teaches**, because a long-lived token is minted five clicks
 * deep in a page most people have never opened. The broker's is **four boxes**, because
 * an address and a login are things whoever set the broker up already chose. No two of
 * those are the same screen with different labels.
 *
 * The third row is also where the enum stops being one-member-per-vendor, and it is worth
 * knowing before reading it that way: a broker has no `SmartHomeVendor` at all, because
 * MQTT is a transport rather than a vendor. See [SmartHomeKind.MQTT].
 */
@Composable
fun AddHubSheet(
    onChoose: (SmartHomeKind) -> Unit,
    onDismiss: () -> Unit,
) {
    EditorOverlay(title = stringResource(R.string.smarthome_add_a_hub), onClose = onDismiss) { dismiss ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.smarthome_what_are_you_connecting_to),
                style = MaterialTheme.typography.bodyMedium,
                color = EditorColors.textSecondary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            KindRow(
                title = stringResource(R.string.smarthome_philips_hue),
                subtitle = stringResource(R.string.smarthome_a_bridge_on_your_wi),
                icon = Icons.Filled.Lightbulb,
                onClick = {
                    onChoose(SmartHomeKind.HUE)
                    dismiss()
                },
            )
            KindRow(
                title = stringResource(R.string.smarthome_home_assistant),
                subtitle = stringResource(R.string.smarthome_a_server_you_already_run),
                icon = Icons.Filled.Home,
                onClick = {
                    onChoose(SmartHomeKind.HOME_ASSISTANT)
                    dismiss()
                },
            )
            KindRow(
                title = stringResource(R.string.smarthome_mqtt_broker),
                subtitle = stringResource(R.string.smarthome_mqtt_broker_subtitle),
                icon = Icons.Filled.Sensors,
                onClick = {
                    onChoose(SmartHomeKind.MQTT)
                    dismiss()
                },
            )
        }
    }
}

@Composable
private fun KindRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
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
                imageVector = icon,
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
