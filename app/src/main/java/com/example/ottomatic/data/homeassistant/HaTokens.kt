package com.example.ottomatic.data.homeassistant

import com.example.ottomatic.data.SmartHomeHubRepository
import com.example.ottomatic.domain.model.HubAuthMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The access token to send, renewing it first when it is about to stop working.
 *
 * **One place, because there are three callers and they must not each have their own
 * idea of when to renew**: the REST transport, the socket as it connects, and the
 * refresh that fills the picker snapshots. A token renewed twice concurrently is two
 * exchanges where the second invalidates the first on some servers, so renewal is
 * serialised per hub — the same per-hub gate `RoutingSmartHome` uses, for a related
 * reason.
 *
 * A **pasted** token passes straight through and nothing here runs: it does not expire,
 * there is nothing to renew from, and [HubAuthMode] is what tells the two apart so no
 * caller has to.
 */
class HaTokens(private val hubs: SmartHomeHubRepository) {

    private val gates = mutableMapOf<String, Mutex>()

    private val gateLock = Any()

    /**
     * The token for [hubId], or null when there is none this device can use.
     *
     * Null is the same answer for a deleted hub, an unreadable credential and a refresh
     * the server refused, because nothing a caller could do differs between them — all
     * three mean "this hub has to be set up again", which is what
     * `SmartHomeHubRepository.needsPairing` reports on the row.
     */
    @Suppress("ReturnCount") // The fast paths that must not reach the network, then renewal.
    suspend fun accessToken(hubId: String): String? {
        val hub = hubs.get(hubId) ?: return null
        val stored = hubs.accessToken(hubId)
        // A pasted token never expires and has nothing to renew from.
        if (hub.authMode != HubAuthMode.OAUTH) return stored
        if (stored != null && !isExpired(hub.tokenExpiresAtEpochMs)) return stored
        return gate(hubId).withLock {
            // Re-read inside the gate: another caller may have renewed while this one
            // waited, and a second exchange would invalidate the first one's answer.
            val current = hubs.get(hubId) ?: return@withLock null
            val fresh = hubs.accessToken(hubId)
            if (fresh != null && !isExpired(current.tokenExpiresAtEpochMs)) fresh else renew(hubId)
        }
    }

    /**
     * Exchanges the stored refresh token for a new access token.
     *
     * A refusal **clears both secrets** rather than leaving them to fail on every
     * subsequent request: `invalid_grant` means the refresh token has been revoked from
     * the user's own profile page, and the only thing that fixes it is signing in again.
     * Clearing is what makes `needsPairing` say so on the hub row, where the alternative
     * is a hub that looks fine and quietly does nothing.
     */
    @Suppress("ReturnCount") // Nothing to renew, or a refusal, or the new token.
    private suspend fun renew(hubId: String): String? {
        val hub = hubs.get(hubId) ?: return null
        val refresh = hubs.refreshToken(hubId) ?: return null
        val (status, body) = withContext(Dispatchers.IO) {
            HaTransport.postForm(hub.host, HaOAuth.TOKEN_PATH, HaOAuth.refreshBody(refresh))
        }
        val tokens = HaOAuth.readTokens(body).takeIf { HaTransport.isSuccess(status) }
        if (tokens == null) {
            hubs.setToken(hubId, accessToken = "", refreshToken = "", mode = HubAuthMode.OAUTH)
            return null
        }
        hubs.setToken(
            id = hubId,
            accessToken = tokens.accessToken,
            // Blank on a refresh response, which leaves the stored one alone. See
            // HaOAuth.readTokens.
            refreshToken = tokens.refreshToken,
            expiresAtEpochMs = HaOAuth.expiryOf(System.currentTimeMillis(), tokens.expiresInSeconds),
            mode = HubAuthMode.OAUTH,
        )
        return tokens.accessToken
    }

    private fun isExpired(expiresAtEpochMs: Long): Boolean =
        expiresAtEpochMs > 0 && System.currentTimeMillis() >= expiresAtEpochMs

    private fun gate(hubId: String): Mutex = synchronized(gateLock) { gates.getOrPut(hubId) { Mutex() } }
}
