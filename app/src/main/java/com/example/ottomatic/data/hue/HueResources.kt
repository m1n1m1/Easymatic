package com.example.ottomatic.data.hue

import com.example.ottomatic.core.service.SmartHomeTargetKind
import com.example.ottomatic.domain.model.SmartHomeResource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One light or group as the bridge reported it, before the facade shapes it. */
internal data class HuePayload(
    val name: String = "",
    val on: Boolean = false,
    val brightnessPercent: Double = 0.0,
    val x: Double = -1.0,
    val y: Double = -1.0,
    val mirek: Int = 0,
    /** The device this light belongs to — how connectivity is looked up. Blank for a group. */
    val ownerRid: String = "",
)

/**
 * Reads the bridge's own resource model into the shape the pickers and the nodes
 * use.
 *
 * Pure, and tested on the JVM against a captured payload, because two of the
 * mappings here are ones that fail *silently*:
 *
 * - **A room's controllable id is its `grouped_light` service's, not the room's.**
 *   Writing to a room's own id is accepted and does nothing. A room with no such
 *   service is dropped rather than listed, because listing it would offer a target
 *   that can never work.
 * - **A light belongs to a room through its *device*, not directly.** The light
 *   names an owner device, and the room lists device children — so the room label a
 *   picker shows is two hops away, and doing it in one produces lights with no room.
 *
 * Everything it does not recognise is ignored, following the app's lenient
 * deserialization rule: a bridge on newer firmware returns resource types this build
 * has never heard of, and none of them are a reason to show an empty list.
 */
@Suppress("TooManyFunctions") // A flat read table plus its field accessors, not branching logic.
internal object HueResources {

    private val json = Json { ignoreUnknownKeys = true }

    /** Every controllable thing in one `GET /clip/v2/resource` payload. */
    fun parseSnapshot(payload: String): List<SmartHomeResource> {
        val data = dataOf(payload)
        val lightData = data.filter { it.type() == "light" }
        val lightsOfDevice = lightData.groupBy({ it.ownerRid() }, { it.id() })
        val roomOfDevice = mutableMapOf<String, String>()
        val nameOfGroup = mutableMapOf<String, String>()
        // What a scene needs to be turned *off*: its group's controllable service,
        // worked out here rather than at run time, where it would cost two GETs.
        val serviceOfGroup = mutableMapOf<String, String>()
        val groups = mutableListOf<SmartHomeResource>()
        data.filter { it.type() == "room" || it.type() == "zone" }.forEach { group ->
            val name = group.name()
            val isRoom = group.type() == "room"
            nameOfGroup[group.id()] = name
            if (isRoom) group.children().forEach { child -> roomOfDevice[child] = name }
            // A room lists *devices* and a zone lists lights, so a room's members
            // are one hop further away than they look — which is the same asymmetry
            // that puts a light's room two hops from the light.
            val members = if (isRoom) {
                group.children().flatMap { lightsOfDevice[it].orEmpty() }
            } else {
                group.children()
            }
            group.serviceRid("grouped_light")?.let { rid ->
                serviceOfGroup[group.id()] = rid
                groups += SmartHomeResource(
                    kind = SmartHomeTargetKind.GROUP,
                    rid = rid,
                    name = name,
                    room = if (isRoom) "" else ZONE,
                    memberRids = members,
                )
            }
        }
        val lights = lightData.map { light ->
            SmartHomeResource(
                kind = SmartHomeTargetKind.LIGHT,
                rid = light.id(),
                name = light.name(),
                room = roomOfDevice[light.ownerRid()].orEmpty(),
                supportsColour = light["color"] != null,
                supportsTemperature = light["color_temperature"] != null,
            )
        }
        val scenes = data.filter { it.type() == "scene" }.map { scene ->
            val groupId = scene.groupRid()
            SmartHomeResource(
                kind = SmartHomeTargetKind.SCENE,
                rid = scene.id(),
                name = scene.name(),
                room = nameOfGroup[groupId].orEmpty(),
                groupRid = serviceOfGroup[groupId].orEmpty(),
            )
        }
        return groups + lights + scenes
    }

    /** One light's or grouped light's current state, from a single-resource GET. */
    fun parseState(payload: String): HuePayload? {
        val resource = dataOf(payload).firstOrNull() ?: return null
        val colour = resource["color"]?.jsonObject?.get("xy")?.jsonObject
        return HuePayload(
            name = resource.name(),
            on = resource["on"]?.jsonObject?.get("on")?.jsonPrimitive?.booleanOrNull ?: false,
            brightnessPercent = resource["dimming"]?.jsonObject
                ?.get("brightness")?.jsonPrimitive?.doubleOrNull ?: 0.0,
            x = colour?.get("x")?.jsonPrimitive?.doubleOrNull ?: -1.0,
            y = colour?.get("y")?.jsonPrimitive?.doubleOrNull ?: -1.0,
            mirek = resource["color_temperature"]?.jsonObject?.get("mirek")?.jsonPrimitive?.intOrNull ?: 0,
            ownerRid = resource.ownerRid(),
        )
    }

    /**
     * Whether the device [ownerRid] is answering, from a
     * `GET /clip/v2/resource/zigbee_connectivity` payload.
     *
     * A bulb switched off at the wall is `connectivity_issue` and everything else is
     * treated as not connected — the failure this answers is "the lamp is dead", and
     * a state this build does not recognise is not evidence that it is alive.
     */
    fun parseReachable(payload: String, ownerRid: String): Boolean = dataOf(payload)
        .any { it.ownerRid() == ownerRid && it["status"]?.jsonPrimitive?.contentOrNull == "connected" }

    /**
     * Which lights are lit right now, from a `GET /clip/v2/resource/light` payload.
     *
     * One request for every light on the bridge rather than one per light, because
     * "change only what is already on" has to ask about a whole room at once and
     * twelve round trips to decide what to write would cost more than the writes.
     */
    fun parseOnByRid(payload: String): Map<String, Boolean> = dataOf(payload).associate { light ->
        light.id() to (light["on"]?.jsonObject?.get("on")?.jsonPrimitive?.booleanOrNull ?: false)
    }

    /**
     * The room or zone one scene belongs to, as `rtype` to `rid`, from a
     * single-scene GET.
     *
     * The network half of what [parseSnapshot] normally precomputes, used when the
     * cached snapshot predates [SmartHomeResource.groupRid]. `rtype` is carried
     * because the follow-up read has to know whether to ask for a room or a zone.
     */
    fun parseSceneGroup(payload: String): Pair<String, String>? {
        val group = dataOf(payload).firstOrNull()?.get("group")?.jsonObject ?: return null
        val rtype = group["rtype"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val rid = group["rid"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return if (rtype.isBlank() || rid.isBlank()) null else rtype to rid
    }

    /** One room's or zone's controllable service, from a single-resource GET. */
    fun parseGroupedLight(payload: String): String? =
        dataOf(payload).firstOrNull()?.serviceRid("grouped_light")

    /** The bridge's own id, from a `GET /clip/v2/resource/bridge` payload. */
    fun parseBridgeId(payload: String): String =
        dataOf(payload).firstOrNull()?.get("bridge_id")?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun dataOf(payload: String): List<JsonObject> = runCatching {
        (json.parseToJsonElement(payload).jsonObject["data"] as? JsonArray)
            ?.map { it.jsonObject }
            .orEmpty()
    }.getOrDefault(emptyList())

    private fun JsonObject.type(): String = this["type"]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun JsonObject.id(): String = this["id"]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun JsonObject.name(): String =
        this["metadata"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun JsonObject.ownerRid(): String =
        this["owner"]?.jsonObject?.get("rid")?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun JsonObject.groupRid(): String =
        this["group"]?.jsonObject?.get("rid")?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun JsonObject.children(): List<String> = this["children"]?.jsonArray
        ?.mapNotNull { it.jsonObject["rid"]?.jsonPrimitive?.contentOrNull }
        .orEmpty()

    private fun JsonObject.serviceRid(rtype: String): String? = this["services"]?.jsonArray
        ?.map { it.jsonObject }
        ?.firstOrNull { it["rtype"]?.jsonPrimitive?.contentOrNull == rtype }
        ?.get("rid")?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotBlank() }

    /**
     * What a zone's `room` field says.
     *
     * A zone has no room — it cuts across them, which is the whole reason it exists —
     * so this is a section label rather than a location, and the picker uses it to
     * keep zones out of the rooms list.
     */
    const val ZONE = "Zone"
}
