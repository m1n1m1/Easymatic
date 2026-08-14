package com.example.ottomatic.data.homeassistant

import com.example.ottomatic.domain.model.HaBaseUrl
import java.net.URLEncoder
import java.security.SecureRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Signing in to Home Assistant, as opposed to pasting a token at it.
 *
 * **Home Assistant implements IndieAuth, not plain OAuth2**, and the difference is the
 * one thing that shapes this whole file: there is no client registration and no client
 * secret. Instead the `client_id` **is a URL**, and Home Assistant *fetches it* during
 * the authorize step to find out which redirect URIs that client is allowed to use. A
 * page at [CLIENT_ID] therefore has to serve, in its first 10 KB:
 *
 * ```html
 * <link rel="redirect_uri" href="ottomatic://ha-auth">
 * ```
 *
 * Two consequences follow, and both are why the pasted token is offered **first** and
 * this second rather than the other way round:
 *
 * - **It needs a page somebody hosts.** Any static host will do — GitHub Pages is
 *   enough — but it is infrastructure outside the app, and until it exists this path
 *   cannot work at all. [isAvailable] is what keeps that from shipping as a button that
 *   fails: with no [CLIENT_ID] the sign-in option is simply not offered, rather than
 *   being offered and then failing at the authorize step with a message about somebody
 *   else's web server.
 * - **It needs the user's Home Assistant to reach the internet.** The server is the
 *   thing that fetches the page, so an air-gapped install can never use OAuth however
 *   well this is written. That is a real and permanent limitation of the mechanism, and
 *   it is the reason a long-lived token is not merely the fallback but the recommended
 *   path — Home Assistant's own documentation points third-party apps at one.
 *
 * The exchange itself is ordinary OAuth2: `authorization_code` for an access token and
 * a refresh token, then `refresh_token` for a new access token as it expires.
 */
internal object HaOAuth {

    /**
     * The page Home Assistant fetches to learn which redirect URIs this app may use.
     *
     * **Blank, deliberately, until somebody hosts one.** Filling this in is the entire
     * cost of enabling the sign-in path: put a page at an https URL you control whose
     * body contains
     *
     * ```html
     * <link rel="redirect_uri" href="ottomatic://ha-auth">
     * ```
     *
     * and put that URL here. Nothing else in the app has to change — [isAvailable]
     * turns the flow on, and the editor grows the button.
     *
     * It must **not** be a URL somebody else controls: whoever serves this page decides
     * which apps Home Assistant will hand tokens to under this client id.
     */
    const val CLIENT_ID = ""

    /**
     * Where the browser comes back to. Matched by the manifest's intent filter, and by
     * the `<link rel="redirect_uri">` on the [CLIENT_ID] page — the two have to agree
     * exactly or Home Assistant refuses the authorize request.
     */
    const val REDIRECT_URI = "ottomatic://ha-auth"

    /**
     * Whether signing in can work at all on this build.
     *
     * The editor asks before drawing the button. A path that cannot succeed is worse
     * than a path that is absent: the failure would arrive at the authorize step, in a
     * browser, worded by Home Assistant, about a URL the user has never heard of.
     */
    val isAvailable: Boolean get() = CLIENT_ID.isNotBlank()

    /** One sign-in attempt: where to send the browser, and the nonce that ties it back. */
    data class Request(val url: String, val state: String)

    /**
     * The authorize URL for [baseUrl], with a fresh nonce.
     *
     * The nonce is 128 bits from `SecureRandom` and is checked when the redirect comes
     * back. That check is the one line stopping another app's browser redirect from
     * completing somebody else's flow — a custom scheme is not exclusive on Android, and
     * anything can send an intent at it.
     */
    @Suppress("ReturnCount") // A bad address and an unconfigured client both mean "cannot".
    fun authorizeRequest(baseUrl: String): Request? {
        val base = HaBaseUrl.parse(baseUrl) ?: return null
        if (!isAvailable) return null
        val state = nonce()
        val url = "$base$AUTHORIZE_PATH" +
            "?client_id=${encode(CLIENT_ID)}" +
            "&redirect_uri=${encode(REDIRECT_URI)}" +
            "&state=${encode(state)}"
        return Request(url, state)
    }

    /** The form body exchanging an authorization code for tokens. */
    fun codeExchangeBody(code: String): String =
        "grant_type=authorization_code&code=${encode(code)}&client_id=${encode(CLIENT_ID)}"

    /** The form body exchanging a refresh token for a new access token. */
    fun refreshBody(refreshToken: String): String =
        "grant_type=refresh_token&refresh_token=${encode(refreshToken)}&client_id=${encode(CLIENT_ID)}"

    /** What a token response carried. */
    data class Tokens(val accessToken: String, val refreshToken: String, val expiresInSeconds: Int)

    /**
     * Reads a token response.
     *
     * A **refresh** response carries no `refresh_token`, which is not a failure and is
     * the normal case: the existing one goes on being valid. It comes back blank here
     * and `SmartHomeHubRepository.setToken` leaves the stored one alone — writing a
     * blank through would sign the user out at the *next* expiry, hours later, with
     * nothing connecting the two events.
     */
    fun readTokens(body: String): Tokens? = runCatching {
        val obj = Json.parseToJsonElement(body).jsonObject
        val access = obj["access_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        access.takeIf { it.isNotBlank() }?.let {
            Tokens(
                accessToken = it,
                refreshToken = obj["refresh_token"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                expiresInSeconds = obj["expires_in"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }
    }.getOrNull()

    /** Home Assistant's own explanation of a refused exchange, when it gave one. */
    fun errorOf(body: String): String? = runCatching {
        val obj = Json.parseToJsonElement(body).jsonObject
        obj["error_description"]?.jsonPrimitive?.contentOrNull
            ?: obj["error"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * When an access token issued now stops being accepted.
     *
     * A minute is taken off, so a request sent as the token expires renews first rather
     * than failing and being retried. `0` for a response that named no lifetime, which
     * [HaTokens] reads as "does not expire".
     */
    fun expiryOf(nowEpochMs: Long, expiresInSeconds: Int): Long =
        if (expiresInSeconds <= 0) 0 else nowEpochMs + (expiresInSeconds * MS_PER_SECOND) - RENEW_MARGIN_MS

    private fun nonce(): String {
        val bytes = ByteArray(NONCE_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    /** The token endpoint, which serves both grant types. */
    const val TOKEN_PATH = "/auth/token"

    private const val AUTHORIZE_PATH = "/auth/authorize"
    private const val NONCE_BYTES = 16
    private const val MS_PER_SECOND = 1_000L

    /** Renew this long before expiry, so a request in flight does not fail on the boundary. */
    private const val RENEW_MARGIN_MS = 60_000L
}
