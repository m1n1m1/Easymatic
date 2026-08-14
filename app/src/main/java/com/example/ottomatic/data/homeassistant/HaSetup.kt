package com.example.ottomatic.data.homeassistant

import com.example.ottomatic.data.SmartHomeHubRepository
import com.example.ottomatic.domain.model.HaBaseUrl
import com.example.ottomatic.domain.model.HubAuthMode
import com.example.ottomatic.domain.model.SmartHomeHub
import com.example.ottomatic.domain.model.SmartHomeKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Connecting a Home Assistant instance and refreshing what it holds — the **editor's**
 * half, which no node has any use for.
 *
 * The counterpart to Hue's pairing, and the shapes of the two are worth contrasting
 * because it explains why `SmartHomeSetup` keeps them apart rather than generalising
 * them. Hue's is a **poll**: press a button within sixty seconds, and the bridge mints a
 * key it will never mint again. This one is a **single exchange** that either works or
 * says why — there is no window, nothing is minted, and the credential can be recreated
 * in a browser at any time. Trying to express both as one flow would put a countdown on
 * a screen that has nothing to count.
 *
 * The rule they *do* share is the one that matters: **the plaintext credential never
 * leaves `data/`.** A token that validates is sealed here and answered with an id.
 */
internal class HaSetup(
    private val hubs: SmartHomeHubRepository,
    private val tokens: HaTokens = HaTokens(hubs),
) {

    /** Whether signing in can work on this build at all. See [HaOAuth.CLIENT_ID]. */
    val canSignIn: Boolean get() = HaOAuth.isAvailable

    /** Where to send the browser, and the nonce to check the answer against. */
    fun signInRequest(baseUrl: String): HaOAuth.Request? = HaOAuth.authorizeRequest(baseUrl)

    /**
     * Exchanges an authorization code for tokens and connects the instance.
     *
     * The **nonce is compared by the caller**, not here: this is handed a code that has
     * already been established as belonging to the flow the user started. See
     * [HaAuthResults] for why that check cannot live at the receiving end.
     */
    @Suppress("ReturnCount") // A bad address, then a refused exchange, then the answer.
    suspend fun completeSignIn(baseUrl: String, code: String): HaConnectResult {
        val base = HaBaseUrl.parse(baseUrl) ?: return HaConnectResult.Failed(HaBaseUrl.REQUIREMENT)
        val (status, body) = withContext(Dispatchers.IO) {
            HaTransport.postForm(base, HaOAuth.TOKEN_PATH, HaOAuth.codeExchangeBody(code))
        }
        val issued = HaOAuth.readTokens(body).takeIf { HaTransport.isSuccess(status) }
            ?: return HaConnectResult.Failed(
                HaOAuth.errorOf(body) ?: HaTransport.problem(status, body, base) ?: SIGN_IN_REFUSED,
            )
        return connect(
            baseUrl = base,
            token = issued.accessToken,
            refreshToken = issued.refreshToken,
            expiresAtEpochMs = HaOAuth.expiryOf(System.currentTimeMillis(), issued.expiresInSeconds),
            mode = HubAuthMode.OAUTH,
        )
    }

    /**
     * Checks [token] against [baseUrl] and answers what is wrong, or null when it
     * works.
     *
     * `GET /api/` is the cheapest authenticated call Home Assistant has — it answers
     * `{"message": "API running."}` — so this proves the address, the reachability and
     * the credential in one round trip without reading a single entity.
     */
    @Suppress("ReturnCount") // Two guards that must never reach the network, then the real answer.
    suspend fun validate(baseUrl: String, token: String): String? {
        if (HaBaseUrl.parse(baseUrl) == null) return HaBaseUrl.REQUIREMENT
        if (token.isBlank()) return "Paste the long-lived access token from your Home Assistant profile"
        val (status, body) = withContext(Dispatchers.IO) { HaTransport.get(baseUrl, token, API_PATH) }
        return HaTransport.problem(status, body, baseUrl)
    }

    /**
     * Creates the hub, seals the credential and reads its first snapshot.
     *
     * Everything happens **before the naming step**, on `SmartHomeSetup.pair`'s
     * reasoning: a user who walks away at the naming screen should be left with a
     * working hub rather than a validated token that was thrown away. It matters less
     * here than it does for Hue — this token can be pasted again — but a half-finished
     * setup is still worse than a named-by-default one.
     */
    @Suppress("ReturnCount") // Each guard names a different thing the user has to fix.
    suspend fun connect(
        baseUrl: String,
        token: String,
        refreshToken: String = "",
        expiresAtEpochMs: Long = 0,
        mode: HubAuthMode = HubAuthMode.TOKEN,
    ): HaConnectResult {
        val base = HaBaseUrl.parse(baseUrl) ?: return HaConnectResult.Failed(HaBaseUrl.REQUIREMENT)
        validate(base, token)?.let { return HaConnectResult.Failed(it) }
        val created = hubs.create(
            SmartHomeHub(
                id = "",
                kind = SmartHomeKind.HOME_ASSISTANT,
                name = nameOf(base, token),
                host = base,
                hardwareId = installIdOf(base, token),
                authMode = mode,
            ),
        )
        val sealed = hubs.setToken(created.id, token, refreshToken, expiresAtEpochMs, mode)
        if (!sealed) {
            // A keystore that will not seal leaves a hub that can never authenticate,
            // and SmartHomeSetup.save's rule applies: delete it rather than leave a row
            // the user would have to work out how to fix.
            hubs.delete(created.id)
            return HaConnectResult.Failed("This device would not store the token — try again")
        }
        refresh(created.id)
        return HaConnectResult.Connected(created.id)
    }

    /**
     * Re-reads everything the pickers draw from: the light-shaped resources, every
     * entity, and every service.
     *
     * Three requests rather than one, and they are written to the hub **together** so a
     * picker opening between two of them cannot render an entity list that disagrees
     * with the light list beside it. A failure leaves the previous snapshot alone,
     * which is what lets a picker keep working with the server switched off.
     */
    @Suppress("ReturnCount") // Two setup failures, then the network answer.
    suspend fun refresh(hubId: String, socketServices: String? = null): String? {
        val hub = hubs.get(hubId) ?: return "That hub has been removed"
        // Through HaTokens rather than the repository: an OAuth hub whose token
        // expired while the phone was off renews here rather than reporting a failure
        // the app can fix by itself.
        val token = tokens.accessToken(hubId) ?: return TOKEN_UNREADABLE.format(hub.name)
        return withContext(Dispatchers.IO) {
            val (statesStatus, statesBody) = HaTransport.get(hub.host, token, STATES_PATH)
            HaTransport.problem(statesStatus, statesBody, hub.host)?.let { return@withContext it }
            val states = HaResources.parseStates(statesBody)

            // Areas and services are best-effort: a template that a locked-down
            // instance refuses, or a services list that fails, must not throw away a
            // states read that worked. The pickers degrade to ungrouped rather than
            // empty, which is a far better answer than "nothing has been read yet".
            val areas = HaResources.parseAreas(renderTemplate(hub.host, token, HaResources.AREAS_TEMPLATE))
            // The socket's answer when there is one, because it is the **only** one carrying
            // `target` and the field `selector`s — `/api/services` returns names and
            // descriptions and nothing else, which is why the first cut of the service picker
            // could not narrow itself no matter what it was asked to filter on. The endpoint
            // stays as the fallback for a refresh taken before any socket is up.
            val servicesBody = socketServices?.takeIf { it.isNotBlank() }
                ?: HaTransport.get(hub.host, token, SERVICES_PATH).second

            hubs.setHomeAssistantSnapshot(
                id = hubId,
                resources = HaResources.resourcesOf(states, areas),
                entities = HaResources.entitiesOf(states, areas),
                services = HaResources.parseServices(servicesBody),
                refreshedAtEpochMs = System.currentTimeMillis(),
            )
            null
        }
    }

    private fun renderTemplate(base: String, token: String, template: String): String {
        val body = Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject { put("template", JsonPrimitive(template)) },
        )
        val (status, rendered) = HaTransport.post(base, token, TEMPLATE_PATH, body)
        return if (HaTransport.isSuccess(status)) rendered else ""
    }

    /**
     * What the instance calls itself, for the name the hub is created with.
     *
     * Best-effort: an instance that will not answer `/api/config` still gets a hub,
     * named after the address it lives at — which is more use than "Home Assistant"
     * repeated once per instance for somebody who runs two.
     */
    private suspend fun nameOf(base: String, token: String): String =
        configField(base, token, "location_name") ?: DEFAULT_NAME

    /**
     * The instance's own uuid, which survives it moving to a new address.
     *
     * The counterpart to a Hue bridge id, and used for the same thing: telling "my hub
     * got a new DHCP lease" from "this is a different hub".
     */
    private suspend fun installIdOf(base: String, token: String): String =
        configField(base, token, "uuid").orEmpty()

    private suspend fun configField(base: String, token: String, field: String): String? {
        val (status, body) = withContext(Dispatchers.IO) { HaTransport.get(base, token, CONFIG_PATH) }
        if (!HaTransport.isSuccess(status)) return null
        return HaTransport.objectOf(body)?.get(field)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val API_PATH = "/api/"
        const val CONFIG_PATH = "/api/config"
        const val STATES_PATH = "/api/states"
        const val SERVICES_PATH = "/api/services"
        const val TEMPLATE_PATH = "/api/template"
        const val DEFAULT_NAME = "Home Assistant"
        const val SIGN_IN_REFUSED = "Home Assistant would not complete the sign-in — try again"
        const val TOKEN_UNREADABLE =
            "The token for \"%s\" could not be read on this device — open Smart home and paste it in again"
    }
}

/** What connecting a Home Assistant instance did, as the setup screen sees it. */
internal sealed interface HaConnectResult {
    /** Connected, stored, and its first snapshot read. The hub exists from this moment. */
    data class Connected(val hubId: String) : HaConnectResult

    /** Stop, and say this. */
    data class Failed(val error: String) : HaConnectResult
}
