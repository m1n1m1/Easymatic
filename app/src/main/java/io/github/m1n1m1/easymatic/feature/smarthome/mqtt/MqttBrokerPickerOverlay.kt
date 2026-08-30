package io.github.m1n1m1.easymatic.feature.smarthome.mqtt

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m1n1m1.easymatic.R
import io.github.m1n1m1.easymatic.domain.model.HubRef
import io.github.m1n1m1.easymatic.domain.model.SmartHomeKind
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorOverlay
import io.github.m1n1m1.easymatic.feature.smarthome.SmartHomeOverlays
import io.github.m1n1m1.easymatic.feature.smarthome.SmartHomeViewModel

private val ROW_SHAPE = RoundedCornerShape(14.dp)

/**
 * Picks one MQTT broker.
 *
 * Its own overlay rather than a fifth mode on `HaPickerOverlay`, and the line is the one
 * that file's own KDoc draws: the four modes there share an overlay because *none of them
 * differs in anything but which list is walked* — they walk one hub's snapshot, they
 * narrow against a scope, they group into sections, they search. This walks the hub
 * library itself, has no scope, no sections and nothing to search through, and stores a
 * different spec. Sharing would mean four dead parameters and a `when` in every branch.
 *
 * It is deliberately **plain**: a household has one broker, occasionally two. The subtitle
 * carries the address and the topic count rather than a description, because those are the
 * two things that distinguish two brokers and the two that say whether a Refresh has ever
 * worked.
 *
 * A broker is **not offered here to be added**, unlike the light picker's "no hub yet"
 * state, but the shared [SmartHomeOverlays] is still composed at the bottom — so a hub
 * whose detail or setup flow is opened from this same ViewModel behaves identically to one
 * opened from the library screen.
 */
@Composable
fun MqttBrokerPickerOverlay(
    viewModel: SmartHomeViewModel,
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    var picked by remember { mutableStateOf<String?>(null) }
    val selectedHubId = HubRef.parse(selected)?.hubId

    EditorOverlay(
        title = stringResource(R.string.mqtt_choose_a_broker),
        onClose = { picked?.let(onPick) ?: onDismiss() },
    ) { dismiss ->
        val brokers = state.hubs.filter { it.kind == SmartHomeKind.MQTT }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (brokers.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.mqtt_no_broker_yet),
                        style = MaterialTheme.typography.bodyMedium,
                        color = EditorColors.textSecondary,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
            brokers.forEach { broker ->
                item(key = broker.id) {
                    BrokerRow(
                        title = broker.name,
                        subtitle = subtitleFor(broker.host, broker.topics.size),
                        selected = broker.id == selectedHubId,
                        onClick = {
                            picked = HubRef.format(broker.id, broker.name)
                            dismiss()
                        },
                    )
                }
            }
        }
    }

    SmartHomeOverlays(state, viewModel)
}

/**
 * The address, and how many topics a Refresh heard.
 *
 * The count is there for [io.github.m1n1m1.easymatic.feature.smarthome.SmartHomeHubList]'s
 * reason: a broker that has never been refreshed and one that was refreshed while nothing
 * was publishing look identical from here, and the difference is the whole of "why is my
 * topic dropdown empty?".
 */
@Composable
private fun subtitleFor(host: String, topics: Int): String = if (topics == 0) {
    host
} else {
    stringResource(
        R.string.mqtt_broker_subtitle,
        host,
        pluralStringResource(R.plurals.mqtt_topics_seen, topics, topics),
    )
}

@Composable
private fun BrokerRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(EditorColors.nodeBackground)
            .border(
                1.dp,
                if (selected) EditorColors.actionAccent else EditorColors.nodeBorder,
                ROW_SHAPE,
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = EditorColors.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = EditorColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
