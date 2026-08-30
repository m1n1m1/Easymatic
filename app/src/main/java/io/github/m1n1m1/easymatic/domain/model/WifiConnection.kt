package io.github.m1n1m1.easymatic.domain.model

/** A network being joined or left, as `trigger.wifi_network` reports it. */
sealed interface WifiTransition {

    /** The name of the network this transition is about. Never blank. */
    val ssid: String

    /** The device has joined [ssid]. */
    data class Connected(override val ssid: String) : WifiTransition

    /** The device has left [ssid]. */
    data class Disconnected(override val ssid: String) : WifiTransition
}

/**
 * Turns a stream of "the Wi-Fi network is currently X" observations into
 * connect/disconnect events.
 *
 * The platform does not report the events. `ConnectivityManager` reports network
 * *presence* — a network became available, its capabilities changed, one was lost —
 * and the name only ever arrives as part of the current state. So the transitions
 * are derived here, which also makes them the only part of this feature that can be
 * tested without a device.
 *
 * Two of the rules are less obvious than they look:
 *
 * - **A disconnect carries the remembered name.** By the time the platform says a
 *   network was lost, it is gone and `WifiManager` can no longer say what it was.
 *   Without the memory, "when I leave the office network" could never name the
 *   office network.
 * - **Roaming is both events.** Moving from one network straight to another emits a
 *   disconnect for the old and a connect for the new, in that order, because a macro
 *   armed on either one is entitled to fire.
 */
class WifiConnectionTracker {

    /** The network currently joined, or null when the device is not on Wi-Fi. */
    private var current: String? = null

    /**
     * Adopts [ssid] as the current state **without emitting anything**.
     *
     * Load-bearing, and the reason this class has two methods rather than one:
     * `registerNetworkCallback` replays whatever is connected the instant it is
     * registered. Treated as an observation, that replay is a connect event for a
     * network the device joined minutes ago — so every macro watching "connected to
     * the home network" would run once on every app start, which is exactly the kind
     * of phantom run that makes an automation app untrustworthy.
     */
    fun seed(ssid: String?) {
        current = ssid?.takeIf { it.isNotBlank() }
    }

    /**
     * Records that the current network is now [ssid] — null or blank meaning the
     * device is not on Wi-Fi — and returns what changed.
     *
     * Empty when nothing did, which is the common case: capability updates arrive
     * repeatedly for a connection that is simply still there.
     */
    fun observe(ssid: String?): List<WifiTransition> {
        val next = ssid?.takeIf { it.isNotBlank() }
        val previous = current
        if (previous == next) return emptyList()
        current = next
        return buildList {
            previous?.let { add(WifiTransition.Disconnected(it)) }
            next?.let { add(WifiTransition.Connected(it)) }
        }
    }
}
