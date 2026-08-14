package com.example.ottomatic.data.smarthome

import com.example.ottomatic.core.service.LightCommand
import com.example.ottomatic.core.service.LightCommandResult
import com.example.ottomatic.core.service.LightRead
import com.example.ottomatic.core.service.LightReading
import com.example.ottomatic.core.service.SceneRecall
import com.example.ottomatic.core.service.SceneResult
import com.example.ottomatic.core.service.SmartHome
import com.example.ottomatic.data.SmartHomeHubRepository
import com.example.ottomatic.domain.model.SmartHomeHub
import com.example.ottomatic.domain.model.SmartHomeKind
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [SmartHome] over whichever vendor the chosen hub speaks.
 *
 * **This is the only class that knows more than one vendor exists**, which is
 * `RoutingAi`'s sentence and its exact shape: `core/` has one [SmartHome] speaking
 * percent, sRGB and kelvin, `engine/` has three nodes, and every mired, `grouped_light`,
 * pinned certificate, `brightness_pct` and `entity_id` stops inside a
 * [SmartHomeVendor].
 *
 * It keeps three things [SmartHome]'s contract depends on, all inherited unchanged from
 * the single-vendor class this replaced:
 *
 * **The hub is resolved on every call** rather than cached, so a hub edited or
 * re-addressed mid-run takes effect immediately and a cache cannot keep sending to an
 * address that has moved.
 *
 * **Commands to one hub are serialised**, per hub rather than globally, because two
 * hubs have no reason to wait for each other. What follows the command is
 * [SmartHomeVendor.commandSpacingMs], which is Hue's 100 ms and Home Assistant's
 * nothing — the serialisation is about *order*, which both want, and the delay is about
 * a bridge that drops what it cannot keep up with, which only one of them has.
 *
 * **Nothing leaves here as an exception.** A deleted hub, a credential this device can
 * no longer open, an unplugged bridge and a revoked token are all results carrying an
 * `error`, so a node downstream sees one shape whatever went wrong.
 */
internal class RoutingSmartHome(
    private val hubs: SmartHomeHubRepository,
    private val vendors: Map<SmartHomeKind, SmartHomeVendor>,
) : SmartHome {

    private val gates = ConcurrentHashMap<String, Mutex>()

    override suspend fun apply(command: LightCommand): LightCommandResult =
        when (val resolved = resolve(command.hubId)) {
            is Resolved.Missing -> LightCommandResult(changed = false, error = resolved.reason)
            is Resolved.Ready -> paced(resolved) { it.apply(resolved.hub, command) }
        }

    override suspend fun recall(request: SceneRecall): SceneResult =
        when (val resolved = resolve(request.hubId)) {
            is Resolved.Missing -> SceneResult(changed = false, error = resolved.reason)
            is Resolved.Ready -> paced(resolved) { it.recall(resolved.hub, request) }
        }

    /**
     * Reading goes through the same gate as writing, deliberately.
     *
     * A read is not what a rate limit is aimed at, but it is what a *toggle* is made
     * of, and letting reads past the gate would let one macro's read overtake another
     * macro's write to the same light and answer the state from before it.
     */
    override suspend fun read(request: LightRead): LightReading =
        when (val resolved = resolve(request.hubId)) {
            is Resolved.Missing -> LightReading(found = false, error = resolved.reason)
            is Resolved.Ready -> paced(resolved) { it.read(resolved.hub, request) }
        }

    private suspend fun <T> paced(resolved: Resolved.Ready, block: suspend (SmartHomeVendor) -> T): T =
        gates.getOrPut(resolved.hub.id) { Mutex() }.withLock {
            withContext(Dispatchers.IO) {
                val result = block(resolved.vendor)
                if (resolved.vendor.commandSpacingMs > 0) delay(resolved.vendor.commandSpacingMs)
                result
            }
        }

    /**
     * A hub and the vendor that speaks to it, or the reason there is neither.
     *
     * Four failures worth telling apart, because they are fixed in four different
     * places: nothing was chosen, the hub is gone, it was never finished being set up,
     * and its credential cannot be read — which on a restored phone is the common one,
     * and is the only one that costs a walk to the hardware.
     */
    private sealed interface Resolved {
        data class Ready(val hub: SmartHomeHub, val vendor: SmartHomeVendor) : Resolved
        data class Missing(val reason: String) : Resolved
    }

    private fun resolve(hubId: String): Resolved {
        val hub = hubs.get(hubId)
        val vendor = hub?.let { vendors[it.kind] }
        return when {
            hubId.isBlank() -> Resolved.Missing("No hub chosen")
            hub == null -> Resolved.Missing("That hub has been removed — open Smart home and add it again")
            // A broker has no lights of its own — see SmartHomeKind.MQTT — so the light
            // pickers never offer one and this is only reachable through hand-edited JSON.
            // Named anyway, because the generic sentence below would send somebody looking
            // for an app update over something no update will ever add.
            hub.kind == SmartHomeKind.MQTT -> Resolved.Missing(
                "\"${hub.name}\" is an MQTT broker, which has no lights of its own — " +
                    "use Publish to MQTT with the topic your device documents",
            )
            // A hub whose vendor this build does not have. Only reachable by moving a
            // workflow to an older app, and worth a sentence rather than a crash.
            vendor == null -> Resolved.Missing("\"${hub.name}\" needs a newer version of Ottomatic")
            !hub.isComplete -> Resolved.Missing(
                "\"${hub.name}\" was never finished being set up — open Smart home and try again",
            )
            else -> vendor.unreachable(hub)?.let { Resolved.Missing(it) } ?: Resolved.Ready(hub, vendor)
        }
    }
}
