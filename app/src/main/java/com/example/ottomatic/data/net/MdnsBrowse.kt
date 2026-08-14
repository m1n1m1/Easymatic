package com.example.ottomatic.data.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import java.net.Inet4Address
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** One service answering an mDNS browse. */
data class MdnsService(
    val host: String,
    val port: Int,
    /** The TXT record, decoded as UTF-8. Absent keys are simply missing. */
    val txt: Map<String, String>,
) {
    fun text(key: String): String = txt[key].orEmpty()
}

/**
 * Browses the local network for a service type.
 *
 * Extracted from `HueDiscovery` when Home Assistant arrived and needed the identical
 * machinery for `_home-assistant._tcp.` — every hard-won detail below is general, and
 * only the service type and how the answer is interpreted are not. Copying it would
 * have meant two places to get the multicast lock and the resolve serialisation right,
 * and both fail *silently* when they are wrong.
 *
 * **An editor-only facade in `data/`**, reached straight from a Composable rather than
 * through `ServiceLocator`, on `WifiNetworks`' stated reasoning: nothing in the engine
 * consumes it. Nothing here is ever on an execution path — a node is handed an address,
 * never asked to find one.
 *
 * **A cold `Flow` rather than a list with a timeout**, because something that answers in
 * 200 ms should be tappable in 200 ms. A fixed wait would make everybody pay for the
 * slowest network on the market, and mDNS has no "that's all of them" to wait for.
 *
 * **Discovery is never the only way in.** Every screen using this keeps a typed address
 * field beside the list, always, and that is not a bolted-on fallback: mDNS is blocked
 * by AP isolation, by guest VLANs, by a good number of mesh routers, and by any setup
 * where the phone is on a band whose segment does not bridge multicast. Those are
 * exactly the networks hardest to debug, and a discovery-only screen is unusable on all
 * of them.
 */
object MdnsBrowse {

    /**
     * Services as they answer, until the collector stops.
     *
     * The **multicast lock is not optional**: Android's Wi-Fi driver filters multicast
     * frames not addressed to the device unless one is held, so without it the browse
     * simply finds nothing — silently, and on some devices and not others, which is the
     * worst way for this to fail. It is held only while somebody is looking at the
     * screen and released in `awaitClose`.
     *
     * [keyOf] decides what counts as a duplicate. It is the caller's because the right
     * answer is vendor-specific: a Hue bridge is identified by the id in its TXT record
     * and falls back to its address, where an instance advertising no id at all has
     * only the address to go on.
     */
    fun services(
        context: Context,
        serviceType: String,
        lockTag: String,
        keyOf: (MdnsService) -> String = { it.host },
    ): Flow<MdnsService> = callbackFlow {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
            ?: return@callbackFlow
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock(lockTag)?.apply {
            setReferenceCounted(true)
            runCatching { acquire() }
        }

        // Resolution is serialised through this channel because two overlapping
        // `resolveService` calls fail the second with FAILURE_ALREADY_ACTIVE — the
        // long-standing NsdManager bug, and the reason a naive browse finds one
        // instance out of two.
        val pending = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val seen = mutableSetOf<String>()

        launch {
            for (service in pending) {
                if (!isActive) break
                resolve(manager, service)?.let { resolved ->
                    if (seen.add(keyOf(resolved))) trySend(resolved)
                }
            }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onServiceFound(service: NsdServiceInfo) {
                pending.trySend(service)
            }
            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)

        awaitClose {
            // Stopping a discovery whose listener never started throws, and a browse
            // that failed to start is precisely when this runs.
            runCatching { manager.stopServiceDiscovery(listener) }
            pending.close()
            runCatching { lock?.release() }
        }
    }

    private suspend fun resolve(manager: NsdManager, service: NsdServiceInfo): MdnsService? =
        runCatching { awaitResolve(manager, service) }.getOrNull()?.let { info ->
            val host = info.hostAddress() ?: return@let null
            MdnsService(host = host, port = info.port, txt = info.textRecord())
        }

    /**
     * One resolution, bounded.
     *
     * The timeout is what keeps a device that stops answering mid-resolve from stalling
     * the serial queue behind it — with no bound, one silent device hides every other
     * one on the network.
     */
    private suspend fun awaitResolve(manager: NsdManager, service: NsdServiceInfo): NsdServiceInfo? =
        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                resolveViaCallback(manager, service)
            } else {
                resolveViaListener(manager, service)
            }
        }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun resolveViaCallback(manager: NsdManager, service: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { continuation ->
            // `onServiceUpdated` fires repeatedly for as long as the callback stays
            // registered; the first answer is the one wanted, and `answered` is what
            // stops a second one resuming an already-resumed continuation.
            var answered = false
            lateinit var callback: NsdManager.ServiceInfoCallback
            fun finish(info: NsdServiceInfo?) {
                if (answered) return
                answered = true
                runCatching { manager.unregisterServiceInfoCallback(callback) }
                if (continuation.isActive) continuation.resume(info)
            }
            callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = finish(null)
                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) = finish(serviceInfo)
                override fun onServiceLost() = finish(null)
                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            continuation.invokeOnCancellation { runCatching { manager.unregisterServiceInfoCallback(callback) } }
            runCatching { manager.registerServiceInfoCallback(service, Executor { it.run() }, callback) }
                .onFailure { finish(null) }
        }

    @Suppress("DEPRECATION")
    private suspend fun resolveViaListener(manager: NsdManager, service: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { continuation ->
            manager.resolveService(
                service,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        if (continuation.isActive) continuation.resume(null)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (continuation.isActive) continuation.resume(serviceInfo)
                    }
                },
            )
        }

    private fun NsdServiceInfo.textRecord(): Map<String, String> =
        attributes.mapNotNull { (key, value) ->
            value?.let { key to String(it, Charsets.UTF_8) }
        }.toMap()

    /**
     * The address to dial.
     *
     * IPv4 is preferred rather than taken as it comes: a device that answers on a
     * link-local IPv6 address needs a zone id to be reachable, and `java.net.URL`
     * cannot carry one — so the v6 answer would parse, connect to nothing, and report
     * a timeout.
     */
    @Suppress("DEPRECATION")
    private fun NsdServiceInfo.hostAddress(): String? {
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            hostAddresses
        } else {
            listOfNotNull(host)
        }
        val chosen = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
        return chosen?.hostAddress?.takeIf { it.isNotBlank() }
    }

    private const val RESOLVE_TIMEOUT_MS = 4_000L
}
