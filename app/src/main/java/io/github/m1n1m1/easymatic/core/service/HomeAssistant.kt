package io.github.m1n1m1.easymatic.core.service

/**
 * Home Assistant, as the engine sees it — the half [SmartHome] cannot express.
 *
 * A second facade beside [SmartHome] rather than more members on it, and the split is
 * along a line that already exists: [SmartHome] is the **vendor-neutral** view of
 * lights, and everything here is Home-Assistant-shaped by construction. An entity id, a
 * service call and a state string have no counterpart on a Hue bridge, so putting them
 * on that interface would mean an implementation whose only honest answer is "not
 * this vendor" — the thing `SmartHomeTargetKind` was kept to three members to avoid.
 *
 * **Nothing here throws.** Every member answers a result carrying an error string, or
 * null, on [SmartHome]'s and [Mail]'s rule: a downstream `action.if` sees one shape
 * whether the entity changed, the server was unplugged or the token was revoked.
 *
 * **This is the first facade both sides of the graph may touch**, and that is the
 * interesting thing about it. [SmartHome], [Mail] and [Ai] are actions' facades and
 * provably so — every member is a network round trip, which is both of the things the
 * pull side may not be. Here the two halves are genuinely different:
 *
 * - [state] is a **map lookup**, served from a cache a websocket keeps warm for as long
 *   as the engine process runs. It is cheap, repeatable and cannot fail, which is the
 *   pull side's actual contract — so `value.ha_state` is legal where `value.light_state`
 *   is not. The bar was never "must not be about the network"; it was "must be cheap
 *   and must not fail", and a push channel clears it.
 * - [call] is a **side effect over the network**, and is an action's alone.
 *
 * Only [Variables] straddles the two this way today, for the same shape of reason: a
 * read is cheap and a write is a thing that happens.
 */
interface HomeAssistant {

    /**
     * One entity's state right now, or null when it cannot be answered.
     *
     * Null covers four cases that a caller cannot act on differently: no such hub, a
     * hub whose credential cannot be read, an entity the cache has never seen, and a
     * connection that has been down long enough for what it holds to be a lie. The
     * consumer falls back to its own form value and a comparison fails closed, which is
     * the declared degradation for a value that cannot read.
     *
     * **Suspending, but not a round trip.** It waits only in one case — a cache that is
     * still filling on a connection that has just opened — and that wait is bounded and
     * happens once per connection rather than once per read.
     */
    suspend fun state(hubId: String, entityId: String): EntityReading?

    /** Calls a service. The action side, and the only member with an effect. */
    suspend fun call(request: ServiceCall): ServiceCallResult

    /** Whether [hubId]'s push connection is live, for the hub detail screen. */
    fun isConnected(hubId: String): Boolean
}

/**
 * One entity as the cache holds it.
 *
 * [state] is Home Assistant's own string — `on`, `off`, `21.4`, `home`, `playing` — and
 * is deliberately **not** converted to a richer type here. What it means depends
 * entirely on the entity, the graph is strictly typed, and `transform.convert` is the
 * visible place a conversion belongs. `value.ha_state` answers text and autocast drops
 * a conversion node in when a numeric port needs one.
 */
data class EntityReading(
    val entityId: String,
    val name: String,
    val state: String,
    /** `°C`, `%`, `kWh`. Blank for anything that is not a measurement. */
    val unit: String = "",
    /** Every attribute, as compact JSON, for `transform.json_read`. */
    val attributes: String = "",
    val changedAtEpochMs: Long = 0,
)

/**
 * One service call.
 *
 * [targetEntityId] is separate from [data] rather than folded into it because it is the
 * one field almost every call needs and the one a picker fills in; everything else is
 * the caller's JSON, passed through. Blank means a call with no target, which is a real
 * thing — `homeassistant.restart` takes none.
 */
data class ServiceCall(
    val hubId: String,
    val domain: String,
    val service: String,
    val targetEntityId: String = "",
    /** A JSON object of extra fields, or blank. Malformed JSON is reported, not sent. */
    val data: String = "",
)

/**
 * [called] rather than "changed", because unlike a light command this cannot know: Home
 * Assistant accepts a service call and reports success without saying whether anything
 * moved. Claiming otherwise would be the one thing this integration must not do.
 */
data class ServiceCallResult(
    val called: Boolean,
    /** A `return_response` payload as JSON, or blank — most services return nothing. */
    val response: String = "",
    val error: String = "",
)

/**
 * No Home Assistant available: every call fails closed, naming the one thing the user
 * can do about it.
 *
 * The engine-only default, so a test that builds an
 * [io.github.m1n1m1.easymatic.engine.ExecutionContext] without a hub sees exactly what a
 * phone with an empty hub library reports — which the nodes already have to handle.
 */
object NoHomeAssistant : HomeAssistant {

    override suspend fun state(hubId: String, entityId: String): EntityReading? = null

    override suspend fun call(request: ServiceCall) = ServiceCallResult(called = false, error = UNAVAILABLE)

    override fun isConnected(hubId: String): Boolean = false

    private const val UNAVAILABLE = "No Home Assistant hub is set up on this phone"
}

/**
 * A connection held open for as long as the engine runs, independent of what is armed.
 *
 * The one facade whose lifetime is the **service's** rather than a run's, and it exists
 * so [io.github.m1n1m1.easymatic.engine.service.MacroEngineService] can own that lifetime
 * without `engine/` learning what is on the other end of it.
 *
 * **Why "as long as the engine runs" rather than "while a trigger is armed".** The
 * obvious design refcounts the connection against armed triggers, which is what
 * `MailWatchers` does and is right there. It is wrong here because of what the socket
 * additionally carries: a warm state cache that `value.ha_state` reads. Tying the
 * socket to armed triggers would mean a value node answering null in every macro that
 * happens not to have a Home Assistant *trigger* in it — which is most of them, and
 * which would look exactly like the node being broken.
 *
 * The honest bound, worth knowing: `MacroEngineService` stops itself when nothing is
 * armed, so in practice this lives on the process scope and is merely *started* by the
 * service. When the process is gone there is nothing running that could read the cache
 * anyway.
 */
interface HubLink {
    fun start()
    fun stop()
}

/** No link: nothing to start, nothing to stop. The engine-only default. */
object NoHubLink : HubLink {
    override fun start() = Unit
    override fun stop() = Unit
}
