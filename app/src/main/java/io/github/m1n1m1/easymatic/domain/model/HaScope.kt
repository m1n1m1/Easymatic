package io.github.m1n1m1.easymatic.domain.model

/**
 * What a scoped Home Assistant picker should narrow to, read off its sibling fields.
 *
 * Pure and JVM-tested for [HaBaseUrl]'s reason: the *interpretation* of a scope list is where
 * this can be wrong, and it can be wrong in a way that shows as an empty chooser rather than as
 * an error. A widget composable would be a poor place to test that.
 *
 * **Blank everywhere means "do not narrow"**, never "narrow to nothing". Every consumer reads it
 * that way, and it is the rule the whole change depends on: a form that silently empties its own
 * choosers is worse than one that was never narrowed.
 */
data class HaScope(
    val hubId: String = "",
    val entityId: String = "",
) {
    /** `light` from `light.desk_lamp`, or blank. What a service list is filtered by. */
    val entityDomain: String get() = entityId.substringBefore('.', missingDelimiterValue = "")

    /** Whether this narrows anything at all. */
    val isEmpty: Boolean get() = hubId.isBlank() && entityId.isBlank()
}

/**
 * The scope named by [specs] — the values of the sibling fields a property declared, in
 * declaration order.
 *
 * Two rules, and both earn their keep:
 *
 * **The hub is the first one found.** That is what makes the backwards-compatibility story free:
 * `scopedBy = ["hub", "service"]` finds it in the explicit `hub` field when that is set, and in
 * the saved service reference when it is blank — so a macro written before the `hub` field
 * existed opens with its pickers correctly scoped and nothing to migrate.
 *
 * **The entity is the last one found**, and it can never be confused with a hub reference,
 * because a hub reference carries a blank id by construction — see
 * [HomeAssistantRef.formatHub]. Last rather than first so that an explicit entity field wins
 * over anything incidental earlier in the list.
 */
fun haScopeOf(specs: List<String>): HaScope {
    val parsed = specs.mapNotNull { HomeAssistantRef.parse(it) }
    return HaScope(
        hubId = parsed.firstOrNull { it.hubId.isNotBlank() }?.hubId.orEmpty(),
        entityId = parsed.lastOrNull { it.id.isNotBlank() }?.id.orEmpty(),
    )
}
