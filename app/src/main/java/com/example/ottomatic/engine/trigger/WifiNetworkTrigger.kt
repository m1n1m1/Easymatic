package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.permissions.PermissionRequirement
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WifiSsid
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.WifiNetwork
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.WifiNetworkEvent
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable

/**
 * Config for `trigger.wifi_network`.
 *
 * [ssid] blank means **any network**, which is what an untouched field says and what
 * the chooser's "Any network" row writes. It is a typed field with a chooser beside
 * it rather than a `@Picker`, because the network somebody is automating for is
 * usually not the one they are standing next to — see
 * [com.example.ottomatic.domain.model.config.WifiNetwork].
 *
 * [event] reuses [ConnectionEvent], the enum `trigger.bluetooth_connect` already
 * filters on: a peripheral connecting or disconnecting is the same question, and a
 * second enum saying the same two words would be a second thing to keep in step with
 * the payload strings.
 */
@Serializable
data class WifiNetworkConfig(
    @Label("Network") @WifiNetwork val ssid: String = "",
    @Label("Event") val event: ConnectionEvent? = null,
)

/**
 * Trigger for `trigger.wifi_network`. Fires when the device joins or leaves a Wi-Fi
 * network, optionally narrowed to one network and one direction.
 *
 * Produces a typed [WifiNetworkEvent] item on the `network` data port.
 *
 * Payload contract with [com.example.ottomatic.data.trigger.WifiNetworkBridge]
 * (string keys):
 * - `triggerType` — `"wifi_network"`
 * - `event` ∈ `"connected"`, `"disconnected"`
 * - `detail` — the network's name, blank when Android would not disclose it
 * - `timestamp` — epoch ms
 *
 * This cannot use [systemStateFlow]: that helper filters on the event alone, and the
 * network filter is the whole point here.
 */
class WifiNetworkTrigger : Trigger<WifiNetworkConfig, WifiNetworkEvent> {

    override val definition = triggerNode<WifiNetworkConfig, WifiNetworkEvent>(
        typeId = TYPE_ID.value,
        displayName = "Wi-Fi Network",
        description = "Starts when the device connects to or disconnects from a Wi-Fi network",
        category = NodeCategory.CONNECTIVITY,
        icon = NodeIcon.WIFI,
        output = dataOut<WifiNetworkEvent>("network", label = "Network"),
        // Location, for a Wi-Fi trigger, which reads like a mistake and is not:
        // knowing *which* network you are on is knowing roughly where you are, so
        // Android has gated the name behind this grant on every version this app
        // supports. Without it the events still arrive and the name does not.
        permissions = listOf(
            PermissionRequirement(
                manifestPermission = Permissions.ACCESS_FINE_LOCATION.manifest,
                type = PrerequisiteType.RUNTIME,
                rationaleKey = "wifi.network",
            ),
        ),
    )

    override fun activate(
        config: WifiNetworkConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<WifiNetworkEvent>> = flow {
        host.report(node, watchingMessage(config))
        // Reported at most once per arm. Every event would say the same thing, and a
        // console filling with one repeated line is a console nobody reads.
        var warnedUnnameable = false

        host.busEvents()
            .filter { it.source == TriggerSource.CONNECTIVITY }
            .filter { it.payload[KEY_TRIGGER_TYPE] == TRIGGER_TYPE }
            .filter { bus -> config.event == null || config.event.payloadValue == bus.payload[KEY_EVENT] }
            .collect { bus ->
                val ssid = WifiSsid.normalise(bus.payload[KEY_DETAIL])
                // The one failure that otherwise looks exactly like a macro that is
                // simply waiting: the trigger is armed, the events arrive, and the
                // filter can never match because nothing is allowed to name them.
                if (ssid.isBlank() && config.ssid.isNotBlank() && !warnedUnnameable) {
                    warnedUnnameable = true
                    host.report(node, UNNAMEABLE_MESSAGE, LogLevel.WARN)
                }
                if (!WifiSsid.matches(config.ssid, ssid)) return@collect
                emit(
                    NodeOutput(
                        WifiNetworkEvent(
                            event = bus.payload[KEY_EVENT].orEmpty(),
                            ssid = ssid,
                            timestamp = bus.timestamp,
                        ),
                    ),
                )
            }
    }

    /** What this node is armed for, so the console says so rather than only the card. */
    private fun watchingMessage(config: WifiNetworkConfig): String {
        val where = if (config.ssid.isBlank()) "any Wi-Fi network" else "'${config.ssid}'"
        val what = when (config.event) {
            null -> "connect and disconnect"
            ConnectionEvent.CONNECTED -> "connect"
            ConnectionEvent.DISCONNECTED -> "disconnect"
        }
        return "Watching $where, on $what"
    }

    companion object {
        val TYPE_ID = NodeTypeId("trigger.wifi_network")

        /** Must match what `WifiNetworkBridge` puts in the payload. */
        const val TRIGGER_TYPE = "wifi_network"

        internal const val UNNAMEABLE_MESSAGE =
            "Android would not name the network, so this trigger's network filter cannot match. " +
                "Grant Location to Ottomatic, or clear the network field to run on any network."
    }
}
