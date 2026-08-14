package com.example.ottomatic.engine

import com.example.ottomatic.core.service.EntityReading
import com.example.ottomatic.core.service.HomeAssistant
import com.example.ottomatic.core.service.ServiceCall
import com.example.ottomatic.core.service.ServiceCallResult

/**
 * A recording [HomeAssistant] for node tests, in [FakeSmartHome]'s shape.
 *
 * The interesting halves are the two things a JVM test cannot otherwise arrange: a
 * **service call that fails** (a revoked token, an instance that is switched off), and a
 * **cache that does not know an entity** — which is the case `value.ha_state`'s whole
 * degradation story rests on, and which happens for real whenever a connection has not
 * finished seeding.
 */
class FakeHomeAssistant(
    var callFailure: String? = null,
    var connected: Boolean = true,
) : HomeAssistant {

    /** What the cache holds, keyed `hubId` to `entityId` to reading. */
    val states = mutableMapOf<String, MutableMap<String, EntityReading>>()

    val calls = mutableListOf<ServiceCall>()

    /** Puts one entity in the cache. */
    fun put(hubId: String, entityId: String, state: String, attributes: String = "{}", unit: String = "") {
        states.getOrPut(hubId) { mutableMapOf() }[entityId] =
            EntityReading(entityId = entityId, name = entityId, state = state, unit = unit, attributes = attributes)
    }

    override suspend fun state(hubId: String, entityId: String): EntityReading? = states[hubId]?.get(entityId)

    override suspend fun call(request: ServiceCall): ServiceCallResult {
        calls += request
        return callFailure
            ?.let { ServiceCallResult(called = false, error = it) }
            ?: ServiceCallResult(called = true)
    }

    override fun isConnected(hubId: String): Boolean = connected
}
