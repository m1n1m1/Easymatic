package io.github.m1n1m1.easymatic.data.homeassistant

import android.content.Context
import io.github.m1n1m1.easymatic.data.net.MdnsBrowse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** One Home Assistant instance answering an mDNS browse, as the setup screen lists it. */
data class DiscoveredInstance(
    /** The full base URL, scheme and port included — ready to store. */
    val baseUrl: String,
    /** What the instance calls itself, or blank. */
    val name: String,
    /** The install `uuid` from the TXT record, or blank. */
    val installId: String,
    val version: String,
)

/**
 * Finds Home Assistant instances on the local network.
 *
 * [MdnsBrowse] does the work; what is here is the service type and the reading of the
 * TXT record. Two details of that reading matter more than they look:
 *
 * **The scheme comes off the `base_url` the instance advertises**, and is not assumed.
 * `HaBaseUrl` deliberately refuses to guess a scheme because guessing `http` for a
 * TLS-terminated instance would send a long-lived access token in the clear — and this
 * is the one place that guess does not have to be made, because the instance says.
 * Falling back to `http` when it advertises nothing is safe in a way the general case
 * is not: a bare mDNS answer on the LAN is a bare mDNS answer on the LAN.
 *
 * **The port comes from the SRV record**, not from a hardcoded 8123. An instance behind
 * a reverse proxy or on a non-default port advertises what it is actually on, and using
 * anything else would produce an address that looks right and connects to nothing.
 */
object HaDiscovery {

    /** Instances as they answer, until the collector stops. */
    fun instances(context: Context): Flow<DiscoveredInstance> =
        MdnsBrowse.services(
            context = context,
            serviceType = SERVICE_TYPE,
            lockTag = LOCK_TAG,
            keyOf = { it.text(TXT_UUID).ifBlank { "${it.host}:${it.port}" } },
        ).map { service ->
            val advertised = service.text(TXT_BASE_URL).takeIf { it.isNotBlank() }
            DiscoveredInstance(
                baseUrl = advertised ?: "$CLEARTEXT_SCHEME${service.host}:${service.port}",
                name = service.text(TXT_LOCATION_NAME),
                installId = service.text(TXT_UUID),
                version = service.text(TXT_VERSION),
            )
        }

    private const val SERVICE_TYPE = "_home-assistant._tcp."
    private const val LOCK_TAG = "easymatic-homeassistant"
    private const val TXT_BASE_URL = "base_url"
    private const val TXT_LOCATION_NAME = "location_name"
    private const val TXT_UUID = "uuid"
    private const val TXT_VERSION = "version"
    private const val CLEARTEXT_SCHEME = "http://"
}
