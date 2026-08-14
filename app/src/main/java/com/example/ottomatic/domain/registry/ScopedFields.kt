package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeTypeId

/**
 * Which config fields a change to one field invalidates.
 *
 * A scoped picker's value is only meaningful **inside its scope**: choose a different hub and
 * the entity beside it is no longer one of that hub's, choose a different entity and the service
 * beside it may be one its domain rejects. So the editor clears them, and this is the pure
 * function that says which.
 *
 * **Why clearing rather than leaving it for the validator.** The validator cannot do this job:
 * it lives in `engine/` and could only ask [SmartHomeHubs] whether the *hub* still exists, not
 * whether `light.turn_on` is still legal beside `lock.front_door` — that needs the catalogue.
 * And a stale value is invisible where a cleared one is not: a picker renders **the name cached
 * inside the reference**, so a service its entity can no longer accept still shows as
 * `light.turn_on` in a perfectly ordinary-looking field. That is the "renders perfectly and does
 * nothing" failure `SmartHomeRef` and `validateSmartHomeRefs` both exist to catch.
 *
 * The cost, plainly: changing the hub on a two-hub install loses the entity and service and
 * costs three taps. It happens only when somebody deliberately moves a node to another server,
 * it is visible the instant it happens rather than at three in the morning, and on the
 * one-instance install nearly everybody has it never fires at all.
 */

/**
 * The keys of [typeId] whose value [changed] invalidates, directly or transitively.
 *
 * **Read-only pickers only**, and that asymmetry is principled rather than convenient: a
 * `@Suggested` value was *typed*, so it means what somebody meant by it, and `on` survives the
 * entity changing to another light. Deleting somebody's typing is a worse failure than leaving
 * a suggestion that no longer applies. The rule in one line — **a picker's value is only
 * meaningful inside its scope; a typed value is meaningful because somebody typed it.**
 *
 * Transitive: changing a hub clears the entity, whose clearing clears the service.
 *
 * **Only ever forwards, and that is what makes a mutual scope legal rather than fatal.** Two
 * fields may perfectly well narrow *each other* — `action.ha_service`'s entity lists what the
 * chosen service accepts, and its service lists what the chosen entity accepts, and both of those
 * are useful — but clearing in both directions means picking either one wipes the other and the
 * form can never hold both. So a change invalidates only what is declared **after** it.
 *
 * Declaration order is form order, so this is the rule a form already implies: you fill it in
 * downwards, and answering a question re-asks the ones below it rather than the ones above. It
 * also breaks any cycle by construction, which is stronger than the visited-set guard it replaced
 * — that terminated, and then cleared the wrong field.
 */
fun keysScopedBy(typeId: NodeTypeId, changed: ConfigKey): Set<ConfigKey> {
    val fields = ConfigSchemaRegistry.byId(typeId)?.fields.orEmpty()
    val changedAt = fields.indexOfFirst { it.key == changed }
    if (changedAt < 0) return emptySet()

    // Only a picker is cleared; a @Suggested field keeps whatever was typed into it. Only a
    // field below the edited one is a candidate — see the KDoc.
    val scopesOf = fields
        .filterIndexed { index, _ -> index > changedAt }
        .mapNotNull { field ->
            (field.type as? ConfigFieldType.PICKER)
                ?.takeIf { it.scopedBy.isNotEmpty() }
                ?.let { field.key to it.scopedBy.map(::ConfigKey).toSet() }
        }
        .toMap()

    val invalidated = mutableSetOf<ConfigKey>()
    var frontier = setOf(changed)
    // A fixpoint rather than one pass, so a chain of any depth resolves.
    while (frontier.isNotEmpty()) {
        val next = scopesOf
            .filterValues { scopes -> scopes.any { it in frontier } }
            .keys
            .filterNot { it in invalidated }
            .toSet()
        invalidated += next
        frontier = next
    }
    return invalidated
}
