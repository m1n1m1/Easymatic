package com.example.ottomatic.data.wifi

import android.content.Context
import android.net.wifi.WifiManager
import com.example.ottomatic.domain.model.WifiSsid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One Wi-Fi network the device can currently see, as the chooser shows it. */
data class WifiNetworkInRange(
    val ssid: String,
    /** Signal strength as a bar count in `0..`[SIGNAL_LEVELS]`- 1`. */
    val signalLevel: Int,
    /** Whether the device is joined to this network right now. */
    val connected: Boolean,
)

/**
 * The Wi-Fi networks in range, for the `@WifiNetwork` field's chooser.
 *
 * Mirrors [com.example.ottomatic.data.apps.InstalledApps] — an editor-only facade in
 * `data/`, reached straight from the Composable rather than through
 * `ServiceLocator`, because nothing in the engine consumes it — with one deliberate
 * difference: **there is no cache.** An app list is stable enough that a process-wide
 * one is a straight win; scan results go stale in seconds, and a chooser confidently
 * listing the networks of the room you left is worse than a short wait.
 *
 * Everything here needs `ACCESS_FINE_LOCATION` *and* location services switched on.
 * Neither is checked here — the caller does, so it can say which is missing — and
 * both merely produce an empty list, because the platform answers a scan it will not
 * serve with no results rather than an error.
 */
object WifiNetworks {

    /**
     * What the device can currently see, strongest first, deduplicated by name.
     *
     * Reads the results the system already holds rather than demanding a fresh scan:
     * Android scans on its own while the screen is on, and every app's results land in
     * the same cache. Deduplication is by SSID because a network worth automating on
     * is usually several access points with one name, and the field stores a name.
     *
     * The currently-connected network is merged in even when it is absent from the
     * results, which happens routinely — a scan can complete while the connection is
     * in a state that excludes it, and "the network I am on right now" missing from
     * the list is the one omission a user would read as a bug.
     */
    @Suppress("DEPRECATION")
    suspend fun inRange(context: Context): List<WifiNetworkInRange> = withContext(Dispatchers.IO) {
        val manager = wifiManager(context) ?: return@withContext emptyList()
        val connected = runCatching {
            WifiSsid.normalise(manager.connectionInfo?.ssid).takeIf { it.isNotBlank() }
        }.getOrNull()

        // `try`/`catch` rather than `runCatching`: a missing ACCESS_FINE_LOCATION is the
        // one thing that goes wrong here and the platform says so by throwing, so naming
        // `SecurityException` is both the honest shape and the one lint can read.
        val results = try {
            manager.scanResults
        } catch (@Suppress("SwallowedException") e: SecurityException) {
            null
        }

        val scanned = results.orEmpty()
            .mapNotNull { result ->
                val ssid = WifiSsid.normalise(result.SSID).takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                ssid to signalLevel(result.level)
            }
            // One name, several access points: keep the strongest sighting of each.
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, levels) -> levels.max() }

        val names = if (connected != null && connected !in scanned) {
            scanned + (connected to SIGNAL_LEVELS - 1)
        } else {
            scanned
        }

        names
            .map { (ssid, level) ->
                WifiNetworkInRange(ssid = ssid, signalLevel = level, connected = ssid == connected)
            }
            .sortedWith(compareByDescending<WifiNetworkInRange> { it.connected }.thenByDescending { it.signalLevel })
    }

    /**
     * Asks the platform for a fresh scan. The results arrive asynchronously, so the
     * caller re-reads [inRange] afterwards rather than getting them back from here.
     *
     * `startScan` is deprecated from API 29 and throttled to four calls per two
     * minutes for a foreground app (far less in the background), with no replacement
     * for "scan now" — which is fine for a **user-initiated tap** and is the only way
     * this may be used. Do not wire it to a poll: the budget is spent in seconds and
     * every other app on the device shares the consequences.
     */
    @Suppress("DEPRECATION")
    fun rescan(context: Context) {
        runCatching { wifiManager(context)?.startScan() }
    }

    private fun wifiManager(context: Context): WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    /**
     * RSSI as bars. `WifiManager.calculateSignalLevel(Int)` takes the bar count on
     * API 30+ and is a different, deprecated method below it, so the arithmetic is
     * done here instead — it is one clamp, and it renders identically on every
     * version rather than differently on either side of an API boundary.
     */
    private fun signalLevel(rssi: Int): Int = when {
        rssi >= EXCELLENT_DBM -> SIGNAL_LEVELS - 1
        rssi <= WORST_DBM -> 0
        else -> ((rssi - WORST_DBM) * (SIGNAL_LEVELS - 1)) / (EXCELLENT_DBM - WORST_DBM)
    }

    /** Bars a row can show, so a level is always in `0..SIGNAL_LEVELS - 1`. */
    const val SIGNAL_LEVELS = 4

    private const val EXCELLENT_DBM = -55
    private const val WORST_DBM = -100
}
