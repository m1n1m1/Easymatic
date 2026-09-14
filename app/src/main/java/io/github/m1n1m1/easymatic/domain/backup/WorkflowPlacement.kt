package io.github.m1n1m1.easymatic.domain.backup

import io.github.m1n1m1.easymatic.domain.model.VariableRef
import io.github.m1n1m1.easymatic.domain.model.Workflow
import io.github.m1n1m1.easymatic.domain.model.reissuedConfig

/**
 * Where a restored workflow lands: under its own id, or as a copy under a fresh one.
 *
 * [originalId] is the id the file carried, kept so the variable values keyed by it can
 * follow the copy — see [mergeVariables].
 */
data class WorkflowPlacement(
    val workflow: Workflow,
    val originalId: String,
    val isCopy: Boolean,
)

/**
 * Decides how [incoming] is added to a phone whose workflows have [existingIds].
 *
 * A **free id is kept**, along with `enabled`: a backup is the user's own phone state, so
 * a macro that was on comes back on, and keeping the id is what lets the variable values
 * and run log filed under it line up again. It is also what makes "I deleted a macro
 * yesterday, restore last week's backup" bring that macro back rather than a stranger.
 *
 * A **colliding id becomes a copy**, on `MacroOperations.duplicate`'s three rules: a fresh
 * id from [newId], a name from [copyName], and [reissuedConfig] per node so the copy
 * does not share the original's API token — two endpoints behind one secret. The copy
 * is **disarmed** for that function's stated reason: two identical macros both listening
 * for the same geofence would each fire, and a backup restored onto the phone that made
 * it — the commonest restore — would otherwise double every armed trigger on the phone.
 *
 * `schemaVersion` is restated in both cases, since a restored file is about to be saved
 * by the current build.
 */
fun placeWorkflow(
    incoming: Workflow,
    existingIds: Set<String>,
    newId: () -> String,
    copyName: (String) -> String,
): WorkflowPlacement {
    if (incoming.id !in existingIds) {
        return WorkflowPlacement(
            workflow = incoming.copy(schemaVersion = Workflow.CURRENT_SCHEMA_VERSION),
            originalId = incoming.id,
            isCopy = false,
        )
    }
    return WorkflowPlacement(
        workflow = incoming.copy(
            id = newId(),
            name = copyName(incoming.name),
            enabled = false,
            schemaVersion = Workflow.CURRENT_SCHEMA_VERSION,
            nodes = incoming.nodes.map { it.copy(config = reissuedConfig(it)) },
        ),
        originalId = incoming.id,
        isCopy = true,
    )
}

/**
 * The variable values after a restore: [local] with [incoming] laid over it, incoming
 * winning where both name a key, and every incoming key scoped to an id in [idRemap]
 * moved under the copy's new id.
 *
 * Incoming wins because the globals *library* was just replaced by the file's, so the
 * file's values are the ones its declarations mean. Local-only keys stay — they belong to
 * macros the file never had. A copy's keys cannot collide with anything: its new id is
 * fresh by construction.
 */
fun mergeVariables(
    local: Map<String, String>,
    incoming: Map<String, String>,
    idRemap: Map<String, String>,
): Map<String, String> {
    val remapped = LinkedHashMap<String, String>(incoming.size)
    for ((key, value) in incoming) {
        val moved = idRemap.entries.firstOrNull { (oldId, _) -> key.startsWith(VariableRef.scopePrefix(oldId)) }
        val target = if (moved == null) {
            key
        } else {
            VariableRef.scopePrefix(moved.value) + key.removePrefix(VariableRef.scopePrefix(moved.key))
        }
        remapped[target] = value
    }
    return local + remapped
}
