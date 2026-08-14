package com.example.ottomatic.data.homeassistant

import com.example.ottomatic.core.service.EntityReading
import com.example.ottomatic.core.service.HomeAssistant
import com.example.ottomatic.core.service.ServiceCall
import com.example.ottomatic.core.service.ServiceCallResult
import com.example.ottomatic.data.SmartHomeHubRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * [HomeAssistant] over the warm cache and the REST API.
 *
 * The two halves are served differently on purpose, and the split is the facade's whole
 * argument. [state] is a **map lookup** into what the websocket has been pushing, which
 * is what makes `value.ha_state` legal on the pull side. [call] is a **round trip**,
 * because a service call is a side effect and there is nothing to read it from.
 *
 * The service call goes over REST rather than the socket even though the socket is
 * usually up, on `HaVendor`'s reasoning: an action must work when it is not — from the
 * editor's preview run, or in the seconds after a reconnect — and a code path that is
 * exercised only when something is broken is a code path that is broken.
 *
 * **Nothing here throws**, which is the facade's promise.
 */
internal class AndroidHomeAssistant(
    private val hubs: SmartHomeHubRepository,
    private val connections: HaConnections,
) : HomeAssistant {

    @Suppress("ReturnCount") // A blank reference and an unknown entity both answer null.
    override suspend fun state(hubId: String, entityId: String): EntityReading? {
        if (hubId.isBlank() || entityId.isBlank()) return null
        val state = connections.state(hubId, entityId) ?: return null
        return EntityReading(
            entityId = state.entityId,
            name = state.friendlyName,
            state = state.state,
            unit = state.attributes[UNIT]?.jsonPrimitive?.content.orEmpty(),
            attributes = Json.encodeToString(JsonObject.serializer(), state.attributes),
            // The cache does not carry a per-entity timestamp: `state_changed` says the
            // change happened, and stamping it here would be this phone's clock rather
            // than the server's. Left at zero rather than filled with a plausible lie.
            changedAtEpochMs = 0,
        )
    }

    override fun isConnected(hubId: String): Boolean = connections.isConnected(hubId)

    @Suppress("ReturnCount") // Each guard names a different thing the user has to fix.
    override suspend fun call(request: ServiceCall): ServiceCallResult {
        if (request.hubId.isBlank()) return failed("No Home Assistant hub chosen on this node")
        if (request.domain.isBlank() || request.service.isBlank()) {
            return failed("No service chosen — pick one like \"light.turn_on\"")
        }
        val hub = hubs.get(request.hubId) ?: return failed(DELETED_HUB)
        val token = hubs.accessToken(request.hubId) ?: return failed(tokenUnreadable(hub.name))
        val body = bodyOf(request) ?: return failed("The extra data is not a JSON object: ${request.data.trim()}")

        val (status, response) = withContext(Dispatchers.IO) {
            HaTransport.post(hub.host, token, "$SERVICES_PATH/${request.domain}/${request.service}", body)
        }
        return when (val problem = HaTransport.problem(status, response, hub.host)) {
            null -> ServiceCallResult(called = true, response = response)
            else -> failed(problem)
        }
    }

    /**
     * The request body: the caller's JSON with the target folded in.
     *
     * The target is merged rather than nested under `target`, because the flat form is
     * what every Home Assistant example uses and what the service schemas accept — and
     * because a caller who put `entity_id` in the data themselves should not end up
     * with two of them. Theirs wins, on the principle that the explicit field is the
     * one they can see.
     *
     * Null means the data is not a JSON object, which is refused **before** the network
     * rather than after: sent through, it would come back as a generic 400 with nothing
     * in it about the box the user actually mistyped.
     */
    private fun bodyOf(request: ServiceCall): String? {
        val extra = request.data.trim().takeIf { it.isNotBlank() }?.let { text ->
            runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        } ?: JsonObject(emptyMap())
        val merged = buildJsonObject {
            if (request.targetEntityId.isNotBlank() && ENTITY_ID !in extra) {
                put(ENTITY_ID, request.targetEntityId)
            }
            extra.forEach { (key, value) -> put(key, value) }
        }
        return Json.encodeToString(JsonObject.serializer(), merged)
    }

    private fun failed(error: String) = ServiceCallResult(called = false, error = error)

    private fun tokenUnreadable(name: String): String =
        "The token for \"$name\" could not be read on this device — open Smart home and paste it in again"

    private companion object {
        const val SERVICES_PATH = "/api/services"
        const val ENTITY_ID = "entity_id"
        const val UNIT = "unit_of_measurement"
        const val DELETED_HUB = "This node points at a Home Assistant hub that no longer exists"
    }
}
