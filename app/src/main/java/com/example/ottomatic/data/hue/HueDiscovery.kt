package com.example.ottomatic.data.hue

import android.content.Context
import com.example.ottomatic.data.net.MdnsBrowse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** One bridge answering an mDNS browse, as the pairing screen lists it. */
data class DiscoveredBridge(
    val host: String,
    /** The `bridgeid` from the TXT record, or blank. Checked against the bridge itself after pairing. */
    val bridgeId: String,
    val modelId: String,
)

/**
 * Finds Hue bridges on the local network.
 *
 * All of the machinery — the multicast lock, the serialised resolve, the API-34 split,
 * the IPv4 preference — moved to [MdnsBrowse] when Home Assistant needed the identical
 * browse for its own service type. What is left here is the only part that was ever
 * Hue's: the service type, and what the TXT record means.
 *
 * The reasoning that machinery carries is in [MdnsBrowse]'s KDoc, and the sentence
 * worth repeating at the call site is this one: **discovery is never the only way in.**
 * The pairing screen keeps a typed address field beside this list, always, because mDNS
 * is blocked by AP isolation, guest VLANs and many mesh routers — exactly the networks
 * hardest to debug.
 *
 * **Deliberately not** falling back to the vendor's cloud discovery endpoint. It is a
 * third-party round trip that reveals the user's public IP, it fails behind CGNAT and
 * on any network where the phone does not share a public IP with the bridge, and the
 * typed address field already covers every case it would.
 */
object HueDiscovery {

    /** Bridges as they answer, until the collector stops. */
    fun bridges(context: Context): Flow<DiscoveredBridge> =
        MdnsBrowse.services(
            context = context,
            serviceType = SERVICE_TYPE,
            lockTag = LOCK_TAG,
            // A bridge advertising an id is identified by it, so the same bridge
            // answering twice is listed once; one advertising none has only its
            // address to go on.
            keyOf = { it.text(TXT_BRIDGE_ID).ifBlank { it.host } },
        ).map { service ->
            DiscoveredBridge(
                host = service.host,
                bridgeId = service.text(TXT_BRIDGE_ID),
                modelId = service.text(TXT_MODEL_ID),
            )
        }

    private const val SERVICE_TYPE = "_hue._tcp."
    private const val LOCK_TAG = "ottomatic-hue"
    private const val TXT_BRIDGE_ID = "bridgeid"
    private const val TXT_MODEL_ID = "modelid"
}
