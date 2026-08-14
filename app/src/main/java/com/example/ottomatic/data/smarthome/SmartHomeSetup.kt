package com.example.ottomatic.data.smarthome

import android.os.Build
import com.example.ottomatic.data.SmartHomeHubRepository
import com.example.ottomatic.data.hue.HueEndpoint
import com.example.ottomatic.data.hue.HueResources
import com.example.ottomatic.data.hue.HueTransport
import com.example.ottomatic.data.hue.PairingOutcome
import com.example.ottomatic.data.homeassistant.HaConnectResult
import com.example.ottomatic.data.homeassistant.HaSetup
import com.example.ottomatic.domain.model.SmartHomeHub
import com.example.ottomatic.domain.model.SmartHomeKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One sign-in attempt: where to send the browser, and the nonce that ties the answer
 * back to it.
 *
 * The nonce is compared when the redirect returns, and that comparison is the one line
 * stopping another app's redirect from completing somebody else's sign-in — a custom URI
 * scheme is not exclusive on Android.
 */
data class SignInRequest(val url: String, val state: String)

/** One attempt at pairing, as the pairing screen sees it. */
sealed interface PairingStep {

    /** The bridge answered that nobody has pressed the button yet. Ask again in a second. */
    data object AwaitingButton : PairingStep

    /** Paired, stored, and its resources read. The hub exists from this moment. */
    data class Paired(val hubId: String) : PairingStep

    /** Stop asking, and say this. */
    data class Failed(val error: String) : PairingStep
}

/**
 * Everything the hub library does that is not simply reading or writing the file:
 * pairing a bridge, refreshing its resource snapshot, and re-pinning a certificate
 * that has changed.
 *
 * A **second** class over the same repository rather than more members on
 * [com.example.ottomatic.core.service.SmartHome], because these are the *editor's*
 * needs and no node has any of them — nothing in a running graph pairs a bridge or
 * asks what lights exist. Routing them through the execution context would put
 * `feature/` on the reading side of a facade built for the engine, which is the
 * reasoning `MailAccountsViewModel.folders` already states for listing mailboxes.
 *
 * It exists at all — rather than the ViewModel calling [HueTransport] directly —
 * so the repository's rule survives: **the plaintext application key never leaves
 * `data/`**. A pairing that succeeds is stored here, sealed here, and answered with
 * an id.
 */
// Two vendors' setup flows plus the shared refresh; the vendors set the count.
@Suppress("TooManyFunctions")
class SmartHomeSetup(private val hubs: SmartHomeHubRepository) {

    private val homeAssistant = HaSetup(hubs)

    /**
     * Checks a Home Assistant address and token together, answering what is wrong or
     * null when they work.
     *
     * The **Test** button behind the setup screen, and it earns its place for
     * `AiConnectionsScreen`'s reason: without it the first proof a credential works is
     * a macro failing quietly at three in the morning, which is exactly the failure
     * this integration exists not to have.
     */
    suspend fun testHomeAssistant(baseUrl: String, token: String): String? =
        homeAssistant.validate(baseUrl, token)

    /**
     * Connects a Home Assistant instance, seals its token and reads its first snapshot.
     *
     * The counterpart to [pair], and shaped differently on purpose: there is no window
     * to count down and nothing is minted, so this either works or says why. See
     * [HaSetup].
     */
    suspend fun connectHomeAssistant(baseUrl: String, token: String): PairingStep =
        homeAssistant.connect(baseUrl, token).asStep()

    /**
     * Whether signing in to Home Assistant can work on this build.
     *
     * False until somebody hosts the IndieAuth client page — see `HaOAuth.CLIENT_ID` —
     * and the setup screen asks before drawing the button. A path that cannot succeed is
     * worse than one that is absent: the failure would arrive in a browser, worded by
     * Home Assistant, about a URL the user has never heard of.
     */
    val canSignInToHomeAssistant: Boolean get() = homeAssistant.canSignIn

    /** Where to send the browser, and the nonce to check the answer against. */
    fun homeAssistantSignIn(baseUrl: String): SignInRequest? =
        homeAssistant.signInRequest(baseUrl)?.let { SignInRequest(it.url, it.state) }

    /** Finishes a sign-in with the code the browser came back with. */
    suspend fun completeHomeAssistantSignIn(baseUrl: String, code: String): PairingStep =
        homeAssistant.completeSignIn(baseUrl, code).asStep()

    private fun HaConnectResult.asStep(): PairingStep = when (this) {
        is HaConnectResult.Connected -> PairingStep.Paired(hubId)
        is HaConnectResult.Failed -> PairingStep.Failed(error)
    }

    /**
     * One poll of the link button on [host].
     *
     * On success this does the whole of the rest of pairing before answering: it
     * creates the hub, seals the keys, pins the certificate it captured during the
     * handshake, checks the bridge is the one mDNS advertised, and reads its
     * resources. The screen that follows only renames it — so a user who walks away
     * at that point still has a working bridge rather than a key that was minted and
     * thrown away. A bridge only ever issues one.
     *
     * [expectedBridgeId] is what mDNS advertised, or blank for a typed address. When
     * it is present it is compared against what the bridge reports over the
     * newly-pinned channel, which is what closes most of the trust-on-first-use
     * window: the two answers come from different protocols, so an attacker would
     * have had to own both. A typed address has nothing independent to compare
     * against, and is accepted on the user's say-so.
     */
    suspend fun pair(host: String, expectedBridgeId: String): PairingStep {
        val outcome = withContext(Dispatchers.IO) { HueTransport.pair(host, deviceType()) }
        return when (outcome) {
            is PairingOutcome.AwaitingButton -> PairingStep.AwaitingButton
            is PairingOutcome.Failed -> PairingStep.Failed(outcome.error)
            is PairingOutcome.Paired -> store(host, expectedBridgeId, outcome)
        }
    }

    /**
     * Re-reads what is on [hubId] into the cached snapshot.
     *
     * Returns null when it worked and a sentence when it did not. Callers that ran
     * this on their own initiative — a screen composing, a picker opening — throw
     * that sentence away and keep the stale list, which is better than an empty one;
     * callers that ran it because the user asked show it.
     */
    suspend fun refresh(hubId: String): String? =
        when (hubs.get(hubId)?.kind) {
            null -> "That hub has been removed"
            SmartHomeKind.HOME_ASSISTANT -> homeAssistant.refresh(hubId)
            SmartHomeKind.HUE -> refreshHue(hubId)
        }

    private suspend fun refreshHue(hubId: String): String? = when (val target = target(hubId)) {
        is Target.Missing -> target.reason
        is Target.Ready -> withContext(Dispatchers.IO) {
            runCatching { HueTransport.get(target.endpoint, RESOURCE_PATH) }
        }.fold(
            onSuccess = { body ->
                hubs.setResources(hubId, HueResources.parseSnapshot(body), System.currentTimeMillis())
                null
            },
            onFailure = { HueTransport.explain(it, target.hub.host) },
        )
    }

    /**
     * Pins whatever [hubId]'s address is presenting now.
     *
     * The way out of a firmware update that rotated the certificate, which a pure
     * trust-on-first-use pin would otherwise turn into "everything stopped working,
     * permanently". It is deliberately a **separate, explicit act** with both
     * fingerprints on screen beside it — trusting silently on mismatch would leave
     * no pin worth having.
     */
    suspend fun trustCurrentCertificate(hubId: String): String? {
        val hub = hubs.get(hubId) ?: return "That hub has been removed"
        val captured = withContext(Dispatchers.IO) { runCatching { HueTransport.captureCertificate(hub.host) } }
        return captured.fold(
            onSuccess = { fingerprint ->
                hubs.setCertificate(hubId, fingerprint)
                null
            },
            onFailure = { HueTransport.explain(it, hub.host) },
        )
    }

    /** What the bridge is presenting right now, for the screen to show beside the pin. */
    suspend fun currentCertificate(hubId: String): String? {
        val hub = hubs.get(hubId) ?: return null
        return withContext(Dispatchers.IO) { runCatching { HueTransport.captureCertificate(hub.host) }.getOrNull() }
    }

    private suspend fun store(host: String, expectedBridgeId: String, paired: PairingOutcome.Paired): PairingStep {
        val endpoint = HueEndpoint(host, paired.applicationKey, paired.certSha256)
        val reported = withContext(Dispatchers.IO) {
            runCatching { HueResources.parseBridgeId(HueTransport.get(endpoint, BRIDGE_PATH)) }.getOrDefault("")
        }
        val impostor = expectedBridgeId.isNotBlank() &&
            reported.isNotBlank() &&
            !reported.equals(expectedBridgeId, ignoreCase = true)
        return if (impostor) PairingStep.Failed(IMPOSTOR) else save(host, reported, paired)
    }

    private suspend fun save(host: String, bridgeId: String, paired: PairingOutcome.Paired): PairingStep {
        val hub = hubs.create(
            SmartHomeHub(
                id = "",
                name = DEFAULT_NAME,
                host = host,
                hardwareId = bridgeId,
                certSha256 = paired.certSha256,
                addedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        val sealed = hubs.setKeys(hub.id, paired.applicationKey, paired.streamKey)
        // A hub whose key could not be sealed can never be talked to, and leaving it
        // in the list would be a row that fails for a reason nothing on it explains.
        if (!sealed) hubs.delete(hub.id) else refresh(hub.id)
        return if (sealed) PairingStep.Paired(hub.id) else PairingStep.Failed(NO_KEYSTORE)
    }

    /** A hub and somewhere to send a request, or the reason there is neither. */
    private sealed interface Target {
        data class Ready(val hub: SmartHomeHub, val endpoint: HueEndpoint) : Target
        data class Missing(val reason: String) : Target
    }

    private fun target(hubId: String): Target {
        val hub = hubs.get(hubId)
        return when {
            hub == null -> Target.Missing("That hub has been removed")
            else -> hubs.applicationKey(hubId)
                ?.let { key -> Target.Ready(hub, HueEndpoint(hub.host, key, hub.certSha256)) }
                ?: Target.Missing("The key for \"${hub.name}\" could not be read. Pair the bridge again.")
        }
    }

    /**
     * What the bridge lists this app as in its own "linked apps" screen.
     *
     * The device half is there so somebody with three phones can tell which one to
     * revoke — the bridge shows nothing else about who asked.
     */
    private fun deviceType(): String = "ottomatic#${Build.MODEL.take(DEVICE_NAME_CHARS)}"

    private companion object {
        const val RESOURCE_PATH = "/clip/v2/resource"
        const val BRIDGE_PATH = "/clip/v2/resource/bridge"
        const val DEFAULT_NAME = "Hue Bridge"
        const val DEVICE_NAME_CHARS = 19
        const val IMPOSTOR = "That is not the bridge this app was pointed at — nothing has been saved"
        const val NO_KEYSTORE = "This phone would not store the key securely, so the bridge was not added"
    }
}
