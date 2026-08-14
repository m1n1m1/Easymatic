package com.example.ottomatic.data.homeassistant

import com.example.ottomatic.core.service.LightCommand
import com.example.ottomatic.core.service.LightCommandResult
import com.example.ottomatic.core.service.LightOp
import com.example.ottomatic.core.service.LightRead
import com.example.ottomatic.core.service.LightReading
import com.example.ottomatic.core.service.SceneOp
import com.example.ottomatic.core.service.SceneRecall
import com.example.ottomatic.core.service.SceneResult
import com.example.ottomatic.core.service.SmartHomeTargetKind
import com.example.ottomatic.data.SmartHomeHubRepository
import com.example.ottomatic.data.smarthome.SmartHomeVendor
import com.example.ottomatic.domain.model.SmartHomeHub

/**
 * Home Assistant, as [SmartHomeVendor] sees it.
 *
 * Everything here goes over **REST**, which is a deliberate choice rather than the
 * websocket being unavailable. `SmartHome`'s three members are documented as network
 * round trips on the exec wire, where the latency is visible and the answer has to be
 * current — and a light command must work whether or not a socket happens to be up,
 * including from the editor's preview run with the engine stopped. The socket's warm
 * cache serves the **pull** side, where a read has to be free; using it here would make
 * `action.light_state` answer from a cache whose age nothing on the node discloses.
 *
 * The differences from [com.example.ottomatic.data.hue.HueVendor] are the argument for
 * the vendor seam sitting at the whole facade:
 *
 * - **A toggle is one call**, because `light.toggle` exists. Hue has to read the state
 *   and send the inverse, which is why its toggle can race somebody at the wall switch
 *   and this one cannot.
 * - **"Only lights already on" is one call with a list**, because a target may name
 *   several entity ids. Hue needs one write per lit light, paced.
 * - **A scene's area is always known**, because the snapshot's area template resolved
 *   it. Hue falls back to two extra requests for a snapshot taken before scenes carried
 *   one.
 */
internal class HaVendor(
    private val hubs: SmartHomeHubRepository,
    private val tokens: HaTokens = HaTokens(hubs),
) : SmartHomeVendor {

    /**
     * No pacing at all, and that is the point of the constant living on the vendor: the
     * 100 ms Hue needs is a fact about a bridge that silently drops what it cannot keep
     * up with. Home Assistant is a server on mains power with a real queue behind it,
     * and paying Hue's tax here would add a second and a half to a fifteen-light
     * `action.for_each` for a ceiling that is not there. Commands are still
     * **serialised** per hub by the router, which is about ordering and is wanted here
     * too.
     */
    override val commandSpacingMs: Long = 0

    override fun unreachable(hub: SmartHomeHub): String? =
        // The repository rather than HaTokens, deliberately: this asks whether there
        // is a credential at all, which renewing cannot change — and it is called before
        // every command, where a network round trip would not belong.
        if (hubs.accessToken(hub.id) == null && hubs.refreshToken(hub.id) == null) {
            TOKEN_UNREADABLE.format(hub.name)
        } else {
            null
        }

    @Suppress("ReturnCount") // An unreadable token, then "nothing was on", then the real answer.
    override suspend fun apply(hub: SmartHomeHub, command: LightCommand): LightCommandResult {
        val token = tokens.accessToken(hub.id)
            ?: return LightCommandResult(changed = false, error = TOKEN_UNREADABLE.format(hub.name))
        val targets = if (command.onlyIfOn && command.op in RESTRICTABLE) {
            val lit = litMembers(hub, token, command)
            // Nothing was on, so nothing changed — and this is not a failure. The node
            // logs it at INFO and says so, exactly as it does for Hue.
            if (lit.isEmpty()) return LightCommandResult(changed = false)
            lit
        } else {
            emptyList()
        }
        val call = HaCommands.callFor(command, targets)
        return when (val problem = send(hub, token, call)) {
            null -> LightCommandResult(changed = true)
            else -> LightCommandResult(changed = false, error = problem)
        }
    }

    @Suppress("ReturnCount") // An unreadable token, then a scene with no area, then the real answer.
    override suspend fun recall(hub: SmartHomeHub, request: SceneRecall): SceneResult {
        val token = tokens.accessToken(hub.id)
            ?: return SceneResult(changed = false, error = TOKEN_UNREADABLE.format(hub.name))
        val groupRid = hub.resources
            .firstOrNull { it.kind == SmartHomeTargetKind.SCENE && it.rid == request.rid }
            ?.groupRid
            .orEmpty()
        // Only a toggle needs to know, and only then is the extra read paid for.
        val sceneIsOn = request.op == SceneOp.TOGGLE && groupRid.isNotBlank() && areaIsLit(hub, token, groupRid)
        val call = HaCommands.sceneCall(request, groupRid, sceneIsOn)
            ?: return SceneResult(changed = false, error = NO_AREA)
        return when (val problem = send(hub, token, call)) {
            null -> SceneResult(changed = true)
            else -> SceneResult(changed = false, error = problem)
        }
    }

    override suspend fun read(hub: SmartHomeHub, request: LightRead): LightReading {
        val token = tokens.accessToken(hub.id)
            ?: return LightReading(found = false, error = TOKEN_UNREADABLE.format(hub.name))
        return if (request.kind == SmartHomeTargetKind.GROUP) {
            readArea(hub, token, request.rid)
        } else {
            readEntity(hub, token, request.rid)
        }
    }

    @Suppress("ReturnCount") // Each failure names a different thing to fix.
    private fun readEntity(hub: SmartHomeHub, token: String, entityId: String): LightReading {
        val (status, body) = HaTransport.get(hub.host, token, "$STATES_PATH/$entityId")
        HaTransport.problem(status, body, hub.host)?.let { return LightReading(found = false, error = it) }
        val state = HaResources.parseState(body) ?: return LightReading(found = false, error = GONE)
        return HaLightState.readingOf(state)
    }

    @Suppress("ReturnCount") // Each failure names a different thing to fix.
    private fun readArea(hub: SmartHomeHub, token: String, rid: String): LightReading {
        val group = hub.resources.firstOrNull { it.kind == SmartHomeTargetKind.GROUP && it.rid == rid }
            ?: return LightReading(found = false, error = GONE)
        val states = allStates(hub, token) ?: return LightReading(found = false, error = UNREACHABLE.format(hub.host))
        val members = states.filter { it.entityId in group.memberRids }
        if (members.isEmpty()) return LightReading(found = false, error = GONE)
        return HaLightState.groupReadingOf(group.name, members)
    }

    /**
     * Which of a command's targets are lit right now.
     *
     * One request for the whole state list rather than one per member, which is the
     * same trade `HueVendor.writeToLit` makes and for the same reason: one heavier
     * request beats a dozen round trips. What follows differs entirely — there the lit
     * ones are written one at a time, here they become a single call's target list.
     */
    @Suppress("ReturnCount") // Two "nothing to narrow to" cases, then the answer.
    private fun litMembers(hub: SmartHomeHub, token: String, command: LightCommand): List<String> {
        val members = if (command.kind == SmartHomeTargetKind.GROUP) {
            hub.resources
                .firstOrNull { it.kind == SmartHomeTargetKind.GROUP && it.rid == command.rid }
                ?.memberRids
                .orEmpty()
        } else {
            listOf(command.rid)
        }
        if (members.isEmpty()) return emptyList()
        val states = allStates(hub, token) ?: return emptyList()
        val on = states.filter { HaLightState.isOn(it.state) }.map { it.entityId }.toSet()
        return members.filter { it in on }
    }

    private fun areaIsLit(hub: SmartHomeHub, token: String, groupRid: String): Boolean {
        val members = hub.resources
            .firstOrNull { it.kind == SmartHomeTargetKind.GROUP && it.rid == groupRid }
            ?.memberRids
            .orEmpty()
        val states = allStates(hub, token).orEmpty()
        return states.any { it.entityId in members && HaLightState.isOn(it.state) }
    }

    private fun allStates(hub: SmartHomeHub, token: String): List<HaResources.HaState>? {
        val (status, body) = HaTransport.get(hub.host, token, STATES_PATH)
        return if (HaTransport.isSuccess(status)) HaResources.parseStates(body) else null
    }

    /** Sends one service call, answering the problem or null. */
    private fun send(hub: SmartHomeHub, token: String, call: HaCommands.ServiceCall): String? {
        val (status, body) = HaTransport.post(
            hub.host,
            token,
            "$SERVICES_PATH/${call.domain}/${call.service}",
            call.data,
        )
        return HaTransport.problem(status, body, hub.host)
    }

    private companion object {
        const val STATES_PATH = "/api/states"
        const val SERVICES_PATH = "/api/services"
        const val TOKEN_UNREADABLE =
            "The token for \"%s\" could not be read on this device — open Smart home and paste it in again"
        const val GONE = "Home Assistant no longer knows that light — open Smart home and refresh it"
        const val UNREACHABLE = "Could not reach Home Assistant at %s"
        const val NO_AREA =
            "That scene is not in any area Home Assistant can switch off — activating it still works"

        /** The operations that can leave a switched-off light alone and still mean something. */
        val RESTRICTABLE = setOf(LightOp.SET_BRIGHTNESS, LightOp.SET_COLOUR, LightOp.SET_TEMPERATURE)
    }
}
