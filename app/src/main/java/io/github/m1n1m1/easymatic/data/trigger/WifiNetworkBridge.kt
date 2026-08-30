package io.github.m1n1m1.easymatic.data.trigger

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.trigger.TriggerBus
import io.github.m1n1m1.easymatic.core.trigger.TriggerEvent
import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.domain.model.WifiConnectionTracker
import io.github.m1n1m1.easymatic.domain.model.WifiSsid
import io.github.m1n1m1.easymatic.domain.model.WifiTransition

/**
 * Runtime-registered bridge reporting which Wi-Fi **network** the device is on, for
 * `trigger.wifi_network`.
 *
 * Registered at runtime rather than in the manifest for [ScreenBroadcastBridge]'s
 * reason: the broadcast that carries this, `android.net.wifi.STATE_CHANGE`, is not on
 * the implicit-broadcast exemption list, so a manifest `<intent-filter>` for it is
 * simply never delivered on API 26+. It does not use that broadcast at all, though —
 * a `NetworkCallback` is the supported route and it reports the connection the
 * platform actually considers current, rather than every supplicant state change on
 * the way there.
 *
 * **One callback for the process, registered unconditionally**, matching
 * [ScreenBroadcastBridge]. It costs one system-side registration whether or not a
 * macro watches for it, and it is what lets [WifiConnectionTracker.seed] do its job:
 * `registerNetworkCallback` replays the currently-connected network the moment it is
 * registered, and absorbing that replay at process start — while nothing is armed —
 * is what stops every "connected to X" macro firing each time the app starts.
 *
 * [TriggerBus.emit] rather than `emitOrHold`: this source exists only while the
 * bridge is registered, which is only while the process lives, so there is no
 * broadcast-woke-the-process race for a held event to solve.
 *
 * Payload contract (consumed by
 * [io.github.m1n1m1.easymatic.engine.trigger.WifiNetworkTrigger]):
 * - `triggerType` — `"wifi_network"`
 * - `event` ∈ `"connected"`, `"disconnected"`
 * - `detail` — the network's name, blank when Android would not disclose it
 * - `timestamp` — epoch ms
 */
class WifiNetworkBridge(context: Context) {

    private val appContext = context.applicationContext
    private val tracker = WifiConnectionTracker()

    /**
     * Whether the callback's initial replay has been absorbed. Guarded by [tracker]
     * because callbacks arrive on a binder thread, and the first two can land close
     * enough together that both would read this as unseeded.
     */
    private var seeded = false

    private val callback = object : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) = update(currentSsid(null))

        // The name usually is not available on onAvailable — the capabilities that
        // carry it settle a moment later — so this is where a connection is normally
        // first nameable, and it fires repeatedly for a connection that has not
        // changed. The tracker filtering unchanged observations is what makes that
        // safe to call on every one.
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            update(currentSsid(capabilities))

        override fun onLost(network: Network) = update(null)
    }

    init {
        runCatching {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            connectivityManager()?.registerNetworkCallback(request, callback)
        }.onFailure { cause -> Log.w(TAG, "not watching Wi-Fi networks: $cause") }
    }

    /** Records the observation and emits whatever it changed. */
    private fun update(ssid: String?) {
        val transitions = synchronized(tracker) {
            if (!seeded) {
                seeded = true
                tracker.seed(ssid)
                return@synchronized emptyList()
            }
            tracker.observe(ssid)
        }
        transitions.forEach(::emit)
    }

    private fun emit(transition: WifiTransition) {
        val event = when (transition) {
            is WifiTransition.Connected -> EVENT_CONNECTED
            is WifiTransition.Disconnected -> EVENT_DISCONNECTED
        }
        TriggerBus.emit(
            TriggerEvent(
                source = TriggerSource.CONNECTIVITY,
                triggerNodeId = NodeId.BROADCAST,
                payload = mapOf(
                    KEY_TRIGGER_TYPE to TRIGGER_TYPE,
                    KEY_EVENT to event,
                    KEY_DETAIL to transition.ssid,
                    KEY_TIMESTAMP to System.currentTimeMillis().toString(),
                ),
            ),
        )
    }

    /**
     * The name of the network now joined, or null when there is none.
     *
     * Two routes, because the modern one does not exist far enough back:
     * `NetworkCapabilities.getTransportInfo()` carries a [WifiInfo] from API 29 and is
     * the only one that names *the network these capabilities describe*, which matters
     * while a handover is in flight. Below that, and when the capabilities are not to
     * hand, `WifiManager` answers for the current connection. Both are redacted the
     * same way without `ACCESS_FINE_LOCATION`, which [WifiSsid.normalise] turns into a
     * blank, and blank into null here.
     */
    @Suppress("DEPRECATION")
    private fun currentSsid(capabilities: NetworkCapabilities?): String? = runCatching {
        val fromCapabilities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (capabilities?.transportInfo as? WifiInfo)?.ssid
        } else {
            null
        }
        val raw = fromCapabilities ?: wifiManager()?.connectionInfo?.ssid
        WifiSsid.normalise(raw).takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun connectivityManager(): ConnectivityManager? =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private fun wifiManager(): WifiManager? =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    companion object {

        /** Must match what `WifiNetworkTrigger` filters on. */
        const val TRIGGER_TYPE = "wifi_network"

        const val EVENT_CONNECTED = "connected"
        const val EVENT_DISCONNECTED = "disconnected"

        const val KEY_TRIGGER_TYPE = "triggerType"
        const val KEY_EVENT = "event"
        const val KEY_DETAIL = "detail"
        const val KEY_TIMESTAMP = "timestamp"

        private const val TAG = "Easymatic"
    }
}
