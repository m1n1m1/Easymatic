package com.example.ottomatic.data.hue

import com.example.ottomatic.core.service.LightCommand
import com.example.ottomatic.core.service.LightCommandResult
import com.example.ottomatic.core.service.LightOp
import com.example.ottomatic.core.service.LightRead
import com.example.ottomatic.core.service.LightReading
import com.example.ottomatic.core.service.SceneOp
import com.example.ottomatic.core.service.SceneRecall
import com.example.ottomatic.core.service.SceneResult
import com.example.ottomatic.core.service.SmartHome
import com.example.ottomatic.core.service.SmartHomeLimits
import com.example.ottomatic.core.service.SmartHomeTargetKind
import com.example.ottomatic.data.SmartHomeHubRepository
import com.example.ottomatic.domain.model.SmartHomeHub
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [SmartHome] over [HueTransport], resolving hubs through the library.
 *
 * This is the class that keeps the facade's promise: **nothing leaves here as an
 * exception**. A deleted hub, a key this device can no longer open, a bridge that is
 * unplugged and a certificate that stopped matching are all results carrying an
 * `error`, so a node downstream sees one shape whatever went wrong.
 *
 * The hub is resolved **on every call** rather than cached, on `AndroidMail`'s
 * reasoning: a hub edited or re-addressed mid-run takes effect immediately, and a
 * cache would otherwise keep sending to an address that has moved.
 *
 * **Commands to one hub are serialised and paced**, which is not politeness. A
 * bridge accepts roughly ten light commands and one group command a second and then
 * starts dropping them *silently* — an `action.for_each` over twenty lights would
 * light twelve of them and report success for all twenty, which is the worst failure
 * shape this integration has. Per hub rather than globally, because two bridges have
 * no reason to wait for each other.
 */
@Suppress("TooManyFunctions") // Three facade members, each with the request it builds; the API sets the count.
class AndroidSmartHome(
    private val hubs: SmartHomeHubRepository,
) : SmartHome {

    private val gates = ConcurrentHashMap<String, Mutex>()

    override suspend fun apply(command: LightCommand): LightCommandResult {
        val resolved = resolve(command.hubId)
        return when (resolved) {
            is Resolved.Missing -> LightCommandResult(changed = false, error = resolved.reason)
            is Resolved.Ready -> paced(command.hubId) {
                runCatching { write(resolved, command) }.fold(
                    // False with no error is not a failure: it is "nothing was on",
                    // which only the `onlyIfOn` path can produce. The node says so.
                    onSuccess = { touched -> LightCommandResult(changed = touched) },
                    onFailure = {
                        LightCommandResult(changed = false, error = HueTransport.explain(it, resolved.hub.host))
                    },
                )
            }
        }
    }

    override suspend fun recall(request: SceneRecall): SceneResult {
        val resolved = resolve(request.hubId)
        return when (resolved) {
            is Resolved.Missing -> SceneResult(changed = false, error = resolved.reason)
            is Resolved.Ready -> paced(request.hubId) {
                runCatching { applyScene(resolved, request) }.fold(
                    onSuccess = { it },
                    onFailure = { SceneResult(changed = false, error = HueTransport.explain(it, resolved.hub.host)) },
                )
            }
        }
    }

    /**
     * Recalling a scene is one request; the other two are about the **group behind
     * it**, because a scene has no off of its own — it is a saved arrangement, and
     * un-recalling one is not a thing the bridge can do. What people mean is
     * turning off the room the scene belongs to, so that is what happens.
     */
    private fun applyScene(resolved: Resolved.Ready, request: SceneRecall): SceneResult =
        when (request.op) {
            SceneOp.ACTIVATE -> activate(resolved, request)
            SceneOp.TURN_OFF, SceneOp.TOGGLE -> switchGroup(resolved, request)
        }

    private fun switchGroup(resolved: Resolved.Ready, request: SceneRecall): SceneResult {
        val group = groupBehind(resolved, request.rid)
        val groupPath = group?.let { HueCommands.pathFor(SmartHomeTargetKind.GROUP, it) }
        return when {
            groupPath == null -> SceneResult(changed = false, error = NO_GROUP)
            // A toggle asks the group first: on means the scene is showing, so turn
            // it off; off means recall the scene. That extra read is why toggle is
            // the one operation here that can race somebody at the wall switch.
            request.op == SceneOp.TOGGLE && !isOn(resolved, groupPath) -> activate(resolved, request)
            else -> {
                HueTransport.put(resolved.endpoint, groupPath, HueCommands.onOffBody(false, request.transitionMs))
                SceneResult(changed = true)
            }
        }
    }

    private fun isOn(resolved: Resolved.Ready, groupPath: String): Boolean =
        HueResources.parseState(HueTransport.get(resolved.endpoint, groupPath))?.on == true

    private fun activate(resolved: Resolved.Ready, request: SceneRecall): SceneResult {
        HueTransport.put(
            resolved.endpoint,
            HueCommands.pathFor(SmartHomeTargetKind.SCENE, request.rid),
            HueCommands.sceneBody(request.transitionMs),
        )
        return SceneResult(changed = true)
    }

    /**
     * The controllable id of the room or zone [sceneRid] belongs to.
     *
     * The cached snapshot already knows this, so the network path exists only for a
     * hub whose snapshot was taken before scenes carried it — two GETs, once, until
     * the next refresh fills it in.
     */
    private fun groupBehind(resolved: Resolved.Ready, sceneRid: String): String? =
        resolved.hub.resources
            .firstOrNull { it.kind == SmartHomeTargetKind.SCENE && it.rid == sceneRid }
            ?.groupRid
            ?.takeIf { it.isNotBlank() }
            ?: askForGroup(resolved, sceneRid)

    private fun askForGroup(resolved: Resolved.Ready, sceneRid: String): String? {
        val scenePath = HueCommands.pathFor(SmartHomeTargetKind.SCENE, sceneRid)
        return HueResources.parseSceneGroup(HueTransport.get(resolved.endpoint, scenePath))
            ?.let { (rtype, rid) ->
                HueResources.parseGroupedLight(HueTransport.get(resolved.endpoint, "$RESOURCE/$rtype/$rid"))
            }
    }

    override suspend fun read(request: LightRead): LightReading {
        val resolved = resolve(request.hubId)
        return when (resolved) {
            is Resolved.Missing -> LightReading(found = false, error = resolved.reason)
            is Resolved.Ready -> withContext(Dispatchers.IO) {
                runCatching { readState(resolved, request) }
                    .getOrElse { LightReading(found = false, error = HueTransport.explain(it, resolved.hub.host)) }
            }
        }
    }

    /**
     * Carries out one command, and answers whether anything was actually written.
     *
     * A toggle is a read then a write, because the bridge has no such request. A
     * command asking to leave switched-off lights alone is a read then *one write
     * per lit light*, because a single request to a group cannot say "but not that
     * one" — see [writeToLit].
     */
    private suspend fun write(resolved: Resolved.Ready, command: LightCommand): Boolean {
        val endpoint = resolved.endpoint
        val effective = if (command.op == LightOp.TOGGLE) {
            val state = HueResources.parseState(
                HueTransport.get(endpoint, HueCommands.pathFor(command.kind, command.rid)),
            )
            command.copy(op = if (state?.on == true) LightOp.TURN_OFF else LightOp.TURN_ON)
        } else {
            command
        }
        // Only the value-setting operations can honour it; an explicit turn-on that
        // skipped a light for being off would do nothing at all.
        if (effective.onlyIfOn && effective.op in RESTRICTABLE) return writeToLit(resolved, effective)
        HueTransport.put(
            endpoint,
            HueCommands.pathFor(effective.kind, effective.rid),
            HueCommands.bodyFor(effective),
        )
        return true
    }

    /**
     * Writes [command] to each light in its target that is **lit right now**.
     *
     * One request cannot express this: a `grouped_light` write reaches every light
     * in the group and there is no "except the ones that are off", so the group is
     * expanded and written a light at a time. That is why the toggle on the node is
     * worded as a cost — a room of twelve lamps is one read and up to twelve
     * writes, paced so the bridge does not silently drop the tail of them.
     *
     * A single light target goes through the same path rather than a special case:
     * "set the desk lamp to 30 % but do not switch it on" is the same request with
     * one member.
     */
    private suspend fun writeToLit(resolved: Resolved.Ready, command: LightCommand): Boolean {
        val members = if (command.kind == SmartHomeTargetKind.GROUP) {
            membersOf(resolved, command.rid)
        } else {
            listOf(command.rid)
        }
        if (members.isEmpty()) return false
        val on = HueResources.parseOnByRid(HueTransport.get(resolved.endpoint, LIGHT_PATH))
        val lit = members.filter { on[it] == true }
        val body = HueCommands.bodyFor(command)
        lit.forEachIndexed { index, rid ->
            // Paced between writes as well as around the call: the per-hub gate
            // spaces one command from the next, and this is many commands.
            if (index > 0) delay(SmartHomeLimits.COMMAND_SPACING_MS)
            HueTransport.put(resolved.endpoint, HueCommands.pathFor(SmartHomeTargetKind.LIGHT, rid), body)
        }
        return lit.isNotEmpty()
    }

    /**
     * The lights inside the group whose controllable id is [groupRid].
     *
     * The snapshot knows this already. The fallback re-reads the whole resource
     * tree rather than walking it a resource at a time, because one heavier request
     * beats three round trips — and it only runs on a snapshot taken before members
     * were recorded, until the next refresh.
     */
    private fun membersOf(resolved: Resolved.Ready, groupRid: String): List<String> {
        val cached = resolved.hub.resources
            .firstOrNull { it.kind == SmartHomeTargetKind.GROUP && it.rid == groupRid }
            ?.memberRids
            .orEmpty()
        if (cached.isNotEmpty()) return cached
        return HueResources.parseSnapshot(HueTransport.get(resolved.endpoint, RESOURCE))
            .firstOrNull { it.kind == SmartHomeTargetKind.GROUP && it.rid == groupRid }
            ?.memberRids
            .orEmpty()
    }

    private fun readState(resolved: Resolved.Ready, request: LightRead): LightReading {
        val payload = HueResources.parseState(
            HueTransport.get(resolved.endpoint, HueCommands.pathFor(request.kind, request.rid)),
        ) ?: return LightReading(found = false, error = GONE)
        val colour = if (payload.x >= 0 && payload.y >= 0) {
            HueColour.rgbFromXy(payload.x, payload.y, payload.brightnessPercent)
        } else {
            LightCommand.NO_COLOUR
        }
        // A group has no device behind it and nothing to ask about connectivity, so
        // the second request is skipped rather than answered from `on` — see LightState.
        val reachable = payload.ownerRid.isNotBlank() && HueResources.parseReachable(
            HueTransport.get(resolved.endpoint, CONNECTIVITY_PATH),
            payload.ownerRid,
        )
        return LightReading(
            found = true,
            name = payload.name,
            on = payload.on,
            brightnessPercent = payload.brightnessPercent,
            colourRgb = colour,
            kelvin = HueColour.kelvinFromMirek(payload.mirek),
            reachable = reachable,
        )
    }

    private suspend fun <T> paced(hubId: String, block: suspend () -> T): T =
        gates.getOrPut(hubId) { Mutex() }.withLock {
            withContext(Dispatchers.IO) {
                val result = block()
                delay(SmartHomeLimits.COMMAND_SPACING_MS)
                result
            }
        }

    /**
     * A hub and the credentials to reach it, or the reason there are not any.
     *
     * Three failures worth telling apart, because they are fixed in three different
     * places: nothing was chosen, the hub is gone, and the key cannot be read —
     * which on a restored phone is the common one, and is the only one that costs a
     * walk to the bridge.
     */
    private sealed interface Resolved {
        data class Ready(val hub: SmartHomeHub, val endpoint: HueEndpoint) : Resolved
        data class Missing(val reason: String) : Resolved
    }

    private fun resolve(hubId: String): Resolved {
        val hub = hubs.get(hubId)
        return when {
            hubId.isBlank() -> Resolved.Missing("No hub chosen")
            hub == null -> Resolved.Missing("That hub has been removed — open Smart home and add it again")
            !hub.isComplete -> Resolved.Missing(
                "\"${hub.name}\" was never finished pairing — open Smart home and try again",
            )
            else -> hubs.applicationKey(hubId)
                ?.let { key -> Resolved.Ready(hub, HueEndpoint(hub.host, key, hub.certSha256)) }
                ?: Resolved.Missing(
                    "The key for \"${hub.name}\" could not be read. " +
                        "Open Smart home and pair the bridge again.",
                )
        }
    }

    private companion object {
        const val GONE = "The hub no longer knows that light — open Smart home and refresh it"
        const val RESOURCE = "/clip/v2/resource"
        const val CONNECTIVITY_PATH = "$RESOURCE/zigbee_connectivity"
        const val LIGHT_PATH = "$RESOURCE/light"

        /** The operations that can leave a switched-off light alone and still mean something. */
        val RESTRICTABLE = setOf(LightOp.SET_BRIGHTNESS, LightOp.SET_COLOUR, LightOp.SET_TEMPERATURE)
        const val NO_GROUP =
            "That scene is not in any room the hub can switch off — activating it still works"
    }
}
