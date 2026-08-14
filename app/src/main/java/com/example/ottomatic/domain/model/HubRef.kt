package com.example.ottomatic.domain.model

/**
 * A durable handle on one hub and nothing on it: which hub, and the name it had when it
 * was chosen.
 *
 * ```
 * hub:<hubId>|<name>
 * ```
 *
 * [SmartHomeRef]'s and [HomeAssistantRef]'s shape with everything vendor-shaped taken
 * out, and it carries their two load-bearing decisions unchanged. Stored as **text**, so
 * it is an ordinary `String` config property. The **name cached inside the spec**, so a
 * config form renders "Loft broker" with the library out of scope and the machine
 * switched off — display-only, never resolved against.
 *
 * **Why a third spec rather than reusing [HomeAssistantRef].** That one's `HA_HUB`
 * spelling is already exactly this shape — a hub with a blank id — so the temptation is
 * to write `ha:` into an MQTT node's config and move on. It would work and it would be a
 * lie: the prefix is what a reader sees first, and a value that announces itself as Home
 * Assistant while naming a broker is the kind of wrongness that survives until somebody
 * is debugging something else entirely. This is the general spelling, and
 * `PickerKind.HA_HUB` would use it if it were written today; it is not migrated to it
 * because that string is **persisted in saved workflows**, where a changed prefix is a
 * reference that stops parsing.
 *
 * Split with a limit of two, so the name keeps every separator it contains — a broker
 * called "Loft | test" survives. The field before it cannot contain one: it is a UUID.
 */
object HubRef {

    private const val PREFIX = "hub:"
    private const val SEPARATOR = '|'
    private const val PARTS = 2

    // The name is last and unsplit, which is what lets it contain separators.
    private const val HUB = 0
    private const val NAME = 1

    /** The spec naming this hub. */
    fun format(hubId: String, name: String): String = "$PREFIX$hubId$SEPARATOR$name"

    /**
     * What [spec] names, or null when it names nothing usable.
     *
     * Fails closed on anything malformed, for [SmartHomeRef]'s reason: a spec that half
     * parses would name a hub that half exists, and every caller here is about to open a
     * network connection with it.
     */
    fun parse(spec: String): Parsed? {
        val parts = spec.takeIf { it.startsWith(PREFIX) }
            ?.removePrefix(PREFIX)
            ?.split(SEPARATOR, limit = PARTS)
            ?.takeIf { it.size == PARTS }
            ?: return null
        val hubId = parts[HUB]
        return if (hubId.isNotBlank()) Parsed(hubId, parts[NAME]) else null
    }

    data class Parsed(
        val hubId: String,
        /** What it was called when it was chosen. For display only — never resolved against. */
        val name: String,
    )
}
