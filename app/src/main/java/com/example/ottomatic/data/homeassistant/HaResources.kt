package com.example.ottomatic.data.homeassistant

import com.example.ottomatic.core.service.SmartHomeTargetKind
import com.example.ottomatic.domain.model.HaEntity
import com.example.ottomatic.domain.model.HaService
import com.example.ottomatic.domain.model.SmartHomeResource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns what Home Assistant reports into the two lists the pickers draw from.
 *
 * **Pure and JVM-tested**, on `HueResources`' reasoning: this is the half that fails
 * *silently*. A light filed under the wrong area, an area whose member list is empty, a
 * colour capability read the wrong way round — none of them throws, and all of them
 * produce a node that looks right and does nothing or the wrong thing.
 *
 * A light appears in **both** outputs, deliberately: in `resources` because it is a
 * thing the light nodes can act on, and in `entities` because it is a thing with a
 * state worth triggering on and reading. See [HaEntity]'s KDoc.
 */
internal object HaResources {

    /** One state as `/api/states` reports it, reduced to what anything here reads. */
    data class HaState(
        val entityId: String,
        val state: String,
        val attributes: JsonObject,
    ) {
        val friendlyName: String
            get() = attributes["friendly_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: entityId

        val domain: String get() = HaIds.domainOf(entityId)
    }

    /** One area, and which entities Home Assistant files under it. */
    data class HaArea(val id: String, val name: String, val entityIds: List<String>)

    /**
     * The template that answers "what areas are there, and what is in each?".
     *
     * **A rendered template rather than the registry websocket commands**, which is the
     * decision worth writing down: `config/area_registry/list` and its two siblings are
     * the documented way to ask, and they exist only over the socket. Refresh runs from
     * the library screen and from a picker opening, both of which happen with the
     * engine stopped and no socket open — so using them would mean opening a websocket
     * to answer a question, and a picker's first frame would wait on a handshake.
     *
     * It also collapses **three** requests into one: the registry route needs the area
     * list, the device list and the entity list, because an entity's area is either its
     * own or the one its *device* belongs to. `area_entities()` already resolves that
     * two-hop lookup server-side, which is the same asymmetry `HueResources` walks by
     * hand for a Hue room and the reason that function is the most-tested one there.
     */
    const val AREAS_TEMPLATE: String =
        "[{% for a in areas() %}" +
            "{\"id\": {{ a | tojson }}, \"name\": {{ area_name(a) | tojson }}, " +
            "\"entities\": {{ area_entities(a) | tojson }}}" +
            "{% if not loop.last %},{% endif %}" +
            "{% endfor %}]"

    /** Every state in a `/api/states` response. A malformed payload reads as none. */
    fun parseStates(payload: String): List<HaState> = runCatching {
        Json.parseToJsonElement(payload).jsonArray.mapNotNull { element ->
            val obj = element.jsonObject
            val entityId = obj["entity_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            entityId.takeIf { it.isNotBlank() }?.let {
                HaState(
                    entityId = it,
                    state = obj["state"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    attributes = obj["attributes"] as? JsonObject ?: JsonObject(emptyMap()),
                )
            }
        }
    }.getOrDefault(emptyList())

    /** One state, from a `/api/states/{entity_id}` response. Null when it is not one. */
    fun parseState(payload: String): HaState? = runCatching {
        val obj = Json.parseToJsonElement(payload).jsonObject
        val entityId = obj["entity_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        entityId.takeIf { it.isNotBlank() }?.let {
            HaState(
                entityId = it,
                state = obj["state"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                attributes = obj["attributes"] as? JsonObject ?: JsonObject(emptyMap()),
            )
        }
    }.getOrNull()

    /** Every area in an [AREAS_TEMPLATE] rendering. A malformed payload reads as none. */
    fun parseAreas(payload: String): List<HaArea> = runCatching {
        Json.parseToJsonElement(payload).jsonArray.mapNotNull { element ->
            val obj = element.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            id.takeIf { it.isNotBlank() }?.let {
                HaArea(
                    id = it,
                    name = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { n -> n.isNotBlank() } ?: it,
                    entityIds = obj["entities"]?.jsonArray
                        ?.mapNotNull { e -> e.jsonPrimitive.contentOrNull }
                        .orEmpty(),
                )
            }
        }
    }.getOrDefault(emptyList())

    /**
     * The light-shaped view: what the two light pickers list and the three light nodes
     * act on.
     *
     * An area with **no light in it** is dropped rather than listed, on `HueResources`'
     * "a room with no `grouped_light` service is dropped" rule and for its reason:
     * listing it offers a target that can never do anything, and a node pointed at one
     * reports success and changes nothing. A household's areas are mostly not about
     * lights — "Garage" holding one door sensor is normal — so this drops a lot.
     *
     * Areas carry `room = ""`, which puts them in the picker's **Rooms** section rather
     * than its Zones one. That is right rather than a default: Home Assistant has areas
     * and no zones, and [SmartHomeResource.ZONE] is the marker for a Hue concept that
     * has no counterpart here.
     */
    fun resourcesOf(states: List<HaState>, areas: List<HaArea>): List<SmartHomeResource> {
        val areaOfEntity = areas.flatMap { area -> area.entityIds.map { it to area } }.toMap()
        val lights = states.filter { it.domain == LIGHT_DOMAIN }
        val lightIds = lights.map { it.entityId }.toSet()

        val groups = areas.mapNotNull { area ->
            val members = area.entityIds.filter { it in lightIds }
            members.takeIf { it.isNotEmpty() }?.let {
                SmartHomeResource(
                    kind = SmartHomeTargetKind.GROUP,
                    rid = HaIds.areaRid(area.id),
                    name = area.name,
                    memberRids = it,
                )
            }
        }
        val lightRows = lights.map { light ->
            val modes = light.colourModes()
            SmartHomeResource(
                kind = SmartHomeTargetKind.LIGHT,
                rid = light.entityId,
                name = light.friendlyName,
                room = areaOfEntity[light.entityId]?.name.orEmpty(),
                supportsColour = modes.any { it in COLOUR_MODES },
                supportsTemperature = COLOUR_TEMP_MODE in modes,
            )
        }
        val scenes = states.filter { it.domain == SCENE_DOMAIN }.map { scene ->
            val area = areaOfEntity[scene.entityId]
            SmartHomeResource(
                kind = SmartHomeTargetKind.SCENE,
                rid = scene.entityId,
                name = scene.friendlyName,
                room = area?.name.orEmpty(),
                // Always precomputed here, where Hue's is best-effort: turning a scene
                // off means switching off the area behind it, and the template already
                // told us which that is. The two-request network fallback HueVendor
                // keeps for old snapshots has no counterpart on this side.
                groupRid = area?.let { HaIds.areaRid(it.id) }.orEmpty(),
            )
        }
        return groups + lightRows + scenes
    }

    /** The full entity view: everything, for the entity picker and the state cache. */
    fun entitiesOf(states: List<HaState>, areas: List<HaArea>): List<HaEntity> {
        val areaOfEntity = areas.flatMap { area -> area.entityIds.map { it to area } }.toMap()
        return states.map { state ->
            HaEntity(
                entityId = state.entityId,
                name = state.friendlyName,
                domain = state.domain,
                deviceClass = state.attributes[DEVICE_CLASS]?.jsonPrimitive?.contentOrNull.orEmpty(),
                unit = state.attributes[UNIT]?.jsonPrimitive?.contentOrNull.orEmpty(),
                area = areaOfEntity[state.entityId]?.name.orEmpty(),
            )
        }
    }

    /**
     * Every service in a `/api/services` response.
     *
     * The payload is an array of `{"domain": …, "services": {"turn_on": {…}}}`, so the
     * services are the **keys** of an object rather than a list — which is why this
     * cannot be a `@Serializable` data class and is walked by hand.
     */
    fun parseServices(payload: String): List<HaService> = runCatching {
        Json.parseToJsonElement(payload).jsonArray.flatMap { element ->
            val obj = element.jsonObject
            val domain = obj["domain"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val services = obj["services"] as? JsonObject ?: JsonObject(emptyMap())
            if (domain.isBlank()) {
                emptyList()
            } else {
                services.entries.map { (name, spec) ->
                    val detail = spec as? JsonObject
                    HaService(
                        domain = domain,
                        service = name,
                        name = detail?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
                        description = detail?.get("description")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                }
            }
        }
    }.getOrDefault(emptyList())

    /**
     * What a light says it can do.
     *
     * Read from `supported_color_modes` rather than from whether `rgb_color` happens to
     * be present, because attributes reflect the light's **current** mode: a colour
     * bulb sitting in white mode reports no `rgb_color` at all, and reading capability
     * off that would grey out Set colour on a light that supports it perfectly well.
     */
    private fun HaState.colourModes(): List<String> = runCatching {
        attributes[SUPPORTED_COLOR_MODES]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
    }.getOrDefault(emptyList())

    private const val LIGHT_DOMAIN = "light"
    private const val SCENE_DOMAIN = "scene"
    private const val SUPPORTED_COLOR_MODES = "supported_color_modes"
    private const val DEVICE_CLASS = "device_class"
    private const val UNIT = "unit_of_measurement"
    private const val COLOUR_TEMP_MODE = "color_temp"

    /** The modes that mean "this light can be told an actual colour". */
    private val COLOUR_MODES = setOf("hs", "xy", "rgb", "rgbw", "rgbww")
}
