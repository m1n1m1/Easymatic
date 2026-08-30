package io.github.m1n1m1.easymatic.domain.model

/**
 * A durable handle on one thing on one Home Assistant hub: which hub, the id the hub
 * knows it by, and the name it had when it was chosen.
 *
 * ```
 * ha:<hubId>|<id>|<name>
 * ```
 *
 * [SmartHomeRef]'s shape with one field fewer, and every one of that type's arguments
 * applies here unchanged: stored as **text** so it can be an ordinary `String` config
 * property; the **hub inside the reference** rather than in a sibling field, so hub A
 * with an entity belonging to hub B is not a representable state; the **name carried
 * along** so a config form renders "Living room temperature" with the server switched
 * off and no snapshot in scope, display-only and never resolved against.
 *
 * **Why a second spec rather than a fourth `SmartHomeTargetKind`.** That enum lives in
 * `core` and means *what a light node can be pointed at* — its three members are the
 * three things `LightCommand`, `SceneRecall` and `LightRead` accept, and `HueCommands`
 * turns each into a URL path in an exhaustive `when`. An entity is not one of those: a
 * thermostat cannot be recalled and a door sensor cannot be dimmed. Widening the enum
 * would put a fourth branch into every vendor's request-shaping code whose only honest
 * body is "reject this", and would make the light picker's sections a lie. A Home
 * Assistant *light* still gets a [SmartHomeRef] like any other light; this is for
 * everything the light nodes cannot speak to.
 *
 * **Three pickers, one spec**, distinguished by what [id] holds rather than by a `kind`
 * field: an entity id for `HA_ENTITY`, a `domain.service` for `HA_SERVICE`, and blank
 * for `HA_HUB`, where the hub *is* the answer and there is nothing further to name. A
 * `kind` would be a field every reader had to check and no reader could act on — the
 * annotation on the property already says which of the three it is, statically, which
 * is the same reason `@Picker` encodes the chooser rather than a mode enum doing it.
 *
 * Split with a limit of three, so the name keeps every separator it contains. The two
 * fields before it cannot contain one: a UUID, and an entity or service id, which Home
 * Assistant restricts to lowercase letters, digits and underscores around a single dot.
 */
object HomeAssistantRef {

    private const val PREFIX = "ha:"
    private const val SEPARATOR = '|'
    private const val PARTS = 3

    // The name is last and unsplit, which is what lets it contain separators.
    private const val HUB = 0
    private const val ID = 1
    private const val NAME = 2

    /** The spec naming this entity or service. */
    fun format(hubId: String, id: String, name: String): String =
        "$PREFIX$hubId$SEPARATOR$id$SEPARATOR$name"

    /** The spec naming a hub and nothing on it — what `HA_HUB` stores. */
    fun formatHub(hubId: String, name: String): String = format(hubId, id = "", name = name)

    /**
     * What [spec] names, or null when it names nothing usable.
     *
     * Fails closed on anything malformed, for [SmartHomeRef]'s reason. **A blank [id]
     * is not malformed**, which is the one place the two parsers differ: it is how a
     * bare hub reference says it names the hub itself. A blank *hub* still fails, since
     * every one of the three uses needs to know which server to talk to.
     */
    fun parse(spec: String): Parsed? {
        val parts = spec.takeIf { it.startsWith(PREFIX) }
            ?.removePrefix(PREFIX)
            ?.split(SEPARATOR, limit = PARTS)
            ?.takeIf { it.size == PARTS }
            ?: return null
        val hubId = parts[HUB]
        return if (hubId.isNotBlank()) Parsed(hubId, parts[ID], parts[NAME]) else null
    }

    data class Parsed(
        val hubId: String,
        /** An entity id, a `domain.service`, or blank when this names the hub itself. */
        val id: String,
        /** What it was called when it was chosen. For display only — never resolved against. */
        val name: String,
    ) {
        /** `light` from `light.turn_on`, or blank if [id] carries no domain. */
        val domain: String get() = id.substringBefore('.', missingDelimiterValue = "")

        /** `turn_on` from `light.turn_on`, or blank if [id] carries no domain. */
        val service: String get() = id.substringAfter('.', missingDelimiterValue = "")
    }
}

/**
 * The hub id inside a reference, whichever of the three spellings it uses.
 *
 * The validator and `PickerRefFields` ask one question of every hub-scoped config field
 * — *is the hub inside this still set up?* — and that question does not care whether the
 * field holds a light, a scene, an entity, a service, a broker or a bare hub. Without
 * this they would each need a `when` over `PickerKind` to pick a parser, which is a
 * second place for a new picker kind to be forgotten and no compiler to notice.
 *
 * The prefixes are disjoint by construction (`sh:`, `ha:`, `hub:`), so the order these
 * are tried in is arbitrary and no spec can be read as two things.
 *
 * Answers null for anything that parses as none of them, which the validator reads as
 * "nothing to say about it" rather than as a problem — a wired field can hold anything
 * at design time, and the node names what it read when it runs.
 */
fun hubIdOf(spec: String): String? =
    SmartHomeRef.parse(spec)?.hubId
        ?: HomeAssistantRef.parse(spec)?.hubId
        ?: HubRef.parse(spec)?.hubId
