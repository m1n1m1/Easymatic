package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.VariableDeclaration
import com.example.ottomatic.domain.model.VariableRef
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.config.PickerKind
import java.util.UUID

/**
 * The config keys of [typeId] that hold a variable reference.
 *
 * Derived from the node's own config class rather than listed by hand, so a fifth
 * node that takes a variable is covered by the validator and the repair the moment
 * it declares `@Picker(PickerKind.VARIABLE)` — which is the only registration step
 * any other part of the node system needs either.
 */
fun variableRefKeys(typeId: NodeTypeId): List<ConfigKey> =
    pickerRefKeys(typeId, PickerKind.VARIABLE)

/** A repaired graph, plus the global declarations its legacy names became. */
data class VariableRepairResult(
    val workflow: Workflow,
    val adopted: List<VariableDeclaration>,
)

/**
 * Rewrites a workflow saved before variables were declared, so its nodes point at
 * real declarations.
 *
 * A legacy config holds a **name**; a ref is an **id**. Every such name becomes a
 * *global* declaration, and that choice is not arbitrary: `action.set_variable`'s
 * own description promised a value "readable by later runs and other macros", and
 * the store on disk was keyed by bare name with no scope at all — so every existing
 * variable already *was* global. Adopting them as workflow-locals would silently cut
 * any pair of macros that shared one, and would leave every stored value stranded
 * under a key nothing looks for.
 *
 * [globalsByName] maps an existing global's name to its id, so a name already
 * adopted by another workflow resolves to the same declaration rather than a second
 * one. Anything not in it is minted here with a fresh id and [ValueType.TEXT] —
 * which is not a guess either: the store has always been flat text and
 * `value.variable` has always answered with a `String`, so everything downstream is
 * already reading it through a visible conversion. Adopting a narrower type would
 * retype ports underneath edges that currently work.
 *
 * **Idempotent.** A ref that already resolves — a local id in this workflow's own
 * declarations, or a `g:` spec — is left exactly as it is, so running this on every
 * load costs nothing after the first.
 */
fun repairVariableRefs(workflow: Workflow, globalsByName: Map<String, String>): VariableRepairResult {
    val minted = mutableMapOf<String, VariableDeclaration>()
    val localIds = workflow.variables.mapTo(mutableSetOf()) { it.id }

    val nodes = workflow.nodes.map { node ->
        val keys = variableRefKeys(node.typeId).filter { key ->
            val spec = node.config[key]
            !spec.isNullOrBlank() && VariableRef.parse(spec) is VariableRef.Local && spec !in localIds
        }
        if (keys.isEmpty()) {
            node
        } else {
            node.copy(
                config = node.config + keys.associateWith { key ->
                    val legacyName = requireNotNull(node.config[key])
                    val id = globalsByName[legacyName]
                        ?: minted.getOrPut(legacyName) { declarationFor(legacyName) }.id
                    VariableRef.globalSpec(id)
                },
            )
        }
    }

    return VariableRepairResult(
        workflow = if (nodes == workflow.nodes) workflow else workflow.copy(nodes = nodes),
        adopted = minted.values.toList(),
    )
}

private fun declarationFor(legacyName: String) = VariableDeclaration(
    id = UUID.randomUUID().toString(),
    name = legacyName,
    description = "Adopted from a macro that used variables before they were declared.",
)
