package io.github.m1n1m1.easymatic.data.hue

import io.github.m1n1m1.easymatic.core.service.LightCommand
import io.github.m1n1m1.easymatic.core.service.LightCommandResult
import io.github.m1n1m1.easymatic.core.service.LightOp
import io.github.m1n1m1.easymatic.core.service.LightRead
import io.github.m1n1m1.easymatic.core.service.LightReading
import io.github.m1n1m1.easymatic.core.service.SceneOp
import io.github.m1n1m1.easymatic.core.service.SceneRecall
import io.github.m1n1m1.easymatic.core.service.SceneResult
import io.github.m1n1m1.easymatic.core.service.SmartHomeLimits
import io.github.m1n1m1.easymatic.core.service.SmartHomeTargetKind
import io.github.m1n1m1.easymatic.data.SmartHomeHubRepository
import io.github.m1n1m1.easymatic.data.smarthome.SmartHomeVendor
import io.github.m1n1m1.easymatic.domain.model.SmartHomeHub
import kotlinx.coroutines.delay

/**
 * Philips Hue, as [SmartHomeVendor] sees it.
 *
 * This is `AndroidSmartHome`'s body, unchanged. What moved out of it and up into
 * `RoutingSmartHome` is only the part that was never Hue-specific: resolving a hub id
 * against the library, the per-hub gate, and the `runCatching` that keeps the facade's
 * promise. What stayed is everything that mentions a bridge.
 *
 * The pacing constant is declared here rather than read from the router because it is a
 * fact about *this* hardware: a bridge accepts roughly ten light commands and one group
 * command a second and then starts dropping them **silently** — an `action.for_each`
 * over twenty lights would light twelve of them and report success for all twenty,
 * which is the worst failure shape this integration has.
 */
// Three facade members, each with the requests it builds; the bridge's API sets the count.
@Suppress("TooManyFunctions")
internal class HueVendor(private val hubs: SmartHomeHubRepository) : SmartHomeVendor {

    override val commandSpacingMs: Long = SmartHomeLimits.COMMAND_SPACING_MS

    override fun unreachable(hub: SmartHomeHub): String? =
        if (endpointFor(hub) == null) KEY_UNREADABLE.format(hub.name) else null

    override suspend fun apply(hub: SmartHomeHub, command: LightCommand): LightCommandResult {
        val endpoint = endpointFor(hub)
            ?: return LightCommandResult(changed = false, error = KEY_UNREADABLE.format(hub.name))
        return runCatching { write(hub, endpoint, command) }.fold(
            // False with no error is not a failure: it is "nothing was on", which only
            // the `onlyIfOn` path can produce. The node says so.
            onSuccess = { touched -> LightCommandResult(changed = touched) },
            onFailure = { LightCommandResult(changed = false, error = HueTransport.explain(it, hub.host)) },
        )
    }

    override suspend fun recall(hub: SmartHomeHub, request: SceneRecall): SceneResult {
        val endpoint = endpointFor(hub)
            ?: return SceneResult(changed = false, error = KEY_UNREADABLE.format(hub.name))
        return runCatching { applyScene(hub, endpoint, request) }.fold(
            onSuccess = { it },
            onFailure = { SceneResult(changed = false, error = HueTransport.explain(it, hub.host)) },
        )
    }

    override suspend fun read(hub: SmartHomeHub, request: LightRead): LightReading {
        val endpoint = endpointFor(hub)
            ?: return LightReading(found = false, error = KEY_UNREADABLE.format(hub.name))
        return runCatching { readState(endpoint, request) }
            .getOrElse { LightReading(found = false, error = HueTransport.explain(it, hub.host)) }
    }

    private fun endpointFor(hub: SmartHomeHub): HueEndpoint? =
        hubs.applicationKey(hub.id)?.let { key -> HueEndpoint(hub.host, key, hub.certSha256) }

    /**
     * Recalling a scene is one request; the other two are about the **group behind
     * it**, because a scene has no off of its own — it is a saved arrangement, and
     * un-recalling one is not a thing the bridge can do. What people mean is turning
     * off the room the scene belongs to, so that is what happens.
     */
    private fun applyScene(hub: SmartHomeHub, endpoint: HueEndpoint, request: SceneRecall): SceneResult =
        when (request.op) {
            SceneOp.ACTIVATE -> activate(endpoint, request)
            SceneOp.TURN_OFF, SceneOp.TOGGLE -> switchGroup(hub, endpoint, request)
        }

    private fun switchGroup(hub: SmartHomeHub, endpoint: HueEndpoint, request: SceneRecall): SceneResult {
        val group = groupBehind(hub, endpoint, request.rid)
        val groupPath = group?.let { HueCommands.pathFor(SmartHomeTargetKind.GROUP, it) }
        return when {
            groupPath == null -> SceneResult(changed = false, error = NO_GROUP)
            // A toggle asks the group first: on means the scene is showing, so turn
            // it off; off means recall the scene. That extra read is why toggle is
            // the one operation here that can race somebody at the wall switch.
            request.op == SceneOp.TOGGLE && !isOn(endpoint, groupPath) -> activate(endpoint, request)
            else -> {
                HueTransport.put(endpoint, groupPath, HueCommands.onOffBody(false, request.transitionMs))
                SceneResult(changed = true)
            }
        }
    }

    private fun isOn(endpoint: HueEndpoint, groupPath: String): Boolean =
        HueResources.parseState(HueTransport.get(endpoint, groupPath))?.on == true

    private fun activate(endpoint: HueEndpoint, request: SceneRecall): SceneResult {
        HueTransport.put(
            endpoint,
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
    private fun groupBehind(hub: SmartHomeHub, endpoint: HueEndpoint, sceneRid: String): String? =
        hub.resources
            .firstOrNull { it.kind == SmartHomeTargetKind.SCENE && it.rid == sceneRid }
            ?.groupRid
            ?.takeIf { it.isNotBlank() }
            ?: askForGroup(endpoint, sceneRid)

    private fun askForGroup(endpoint: HueEndpoint, sceneRid: String): String? {
        val scenePath = HueCommands.pathFor(SmartHomeTargetKind.SCENE, sceneRid)
        return HueResources.parseSceneGroup(HueTransport.get(endpoint, scenePath))
            ?.let { (rtype, rid) ->
                HueResources.parseGroupedLight(HueTransport.get(endpoint, "$RESOURCE/$rtype/$rid"))
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
    private suspend fun write(hub: SmartHomeHub, endpoint: HueEndpoint, command: LightCommand): Boolean {
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
        if (effective.onlyIfOn && effective.op in RESTRICTABLE) return writeToLit(hub, endpoint, effective)
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
     * worded as a cost — on **this** vendor a room of twelve lamps is one read and up
     * to twelve writes, paced so the bridge does not silently drop the tail of them.
     * Home Assistant expresses the same request as one call with a list, which is the
     * clearest single illustration of why the vendor seam is the whole facade.
     *
     * A single light target goes through the same path rather than a special case:
     * "set the desk lamp to 30 % but do not switch it on" is the same request with
     * one member.
     */
    private suspend fun writeToLit(hub: SmartHomeHub, endpoint: HueEndpoint, command: LightCommand): Boolean {
        val members = if (command.kind == SmartHomeTargetKind.GROUP) {
            membersOf(hub, endpoint, command.rid)
        } else {
            listOf(command.rid)
        }
        if (members.isEmpty()) return false
        val on = HueResources.parseOnByRid(HueTransport.get(endpoint, LIGHT_PATH))
        val lit = members.filter { on[it] == true }
        val body = HueCommands.bodyFor(command)
        lit.forEachIndexed { index, rid ->
            // Paced between writes as well as around the call: the per-hub gate
            // spaces one command from the next, and this is many commands.
            if (index > 0) delay(SmartHomeLimits.COMMAND_SPACING_MS)
            HueTransport.put(endpoint, HueCommands.pathFor(SmartHomeTargetKind.LIGHT, rid), body)
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
    private fun membersOf(hub: SmartHomeHub, endpoint: HueEndpoint, groupRid: String): List<String> {
        val cached = hub.resources
            .firstOrNull { it.kind == SmartHomeTargetKind.GROUP && it.rid == groupRid }
            ?.memberRids
            .orEmpty()
        if (cached.isNotEmpty()) return cached
        return HueResources.parseSnapshot(HueTransport.get(endpoint, RESOURCE))
            .firstOrNull { it.kind == SmartHomeTargetKind.GROUP && it.rid == groupRid }
            ?.memberRids
            .orEmpty()
    }

    private fun readState(endpoint: HueEndpoint, request: LightRead): LightReading {
        val payload = HueResources.parseState(
            HueTransport.get(endpoint, HueCommands.pathFor(request.kind, request.rid)),
        ) ?: return LightReading(found = false, error = GONE)
        val colour = if (payload.x >= 0 && payload.y >= 0) {
            HueColour.rgbFromXy(payload.x, payload.y, payload.brightnessPercent)
        } else {
            LightCommand.NO_COLOUR
        }
        // A group has no device behind it and nothing to ask about connectivity, so
        // the second request is skipped rather than answered from `on` — see LightState.
        val reachable = payload.ownerRid.isNotBlank() && HueResources.parseReachable(
            HueTransport.get(endpoint, CONNECTIVITY_PATH),
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

    private companion object {
        /**
         * On a phone restored from a backup this is the common failure, and it is the
         * only one that costs a walk to the bridge: the keystore key is never backed
         * up, so the sealed application key comes back unreadable.
         */
        const val KEY_UNREADABLE =
            "The key for \"%s\" could not be read. Open Smart home and pair the bridge again."
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
