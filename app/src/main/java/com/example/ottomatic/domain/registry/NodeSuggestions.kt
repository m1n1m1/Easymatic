package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeTypeDefinition
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.schema.ItemSchema

/**
 * The endpoint a connection drag started from, resolved to its *effective*
 * schema (dynamic-port nodes must be resolved via [effectivePorts] before
 * building this).
 */
data class DragOrigin(
    val kind: PortKind,
    val isOutput: Boolean,
    val schema: ItemSchema?,
)

/**
 * A node type that can be placed and immediately wired to the dragged port,
 * together with the port on the new node that the edge should land on.
 */
data class NodeSuggestion(
    val definition: NodeTypeDefinition,
    val port: Port,
)

/**
 * True iff a DATA edge from [source] to [target] satisfies the structural
 * subtyping rule. The single definition of that rule: used by the editor at
 * drop time, by the node suggestions below and by
 * [com.example.ottomatic.engine.validation.GraphValidator], which runs
 * continuously in the editor and again on the snapshot each run walks.
 *
 * A missing port or missing schema is treated as [ItemSchema.Wildcard], which
 * accepts (and is accepted by) anything.
 */
fun isDataAssignable(source: Port?, target: Port?): Boolean {
    val sourceSchema = source?.schema ?: ItemSchema.Wildcard
    val targetSchema = target?.schema ?: ItemSchema.Wildcard
    return targetSchema.isAssignableFrom(sourceSchema)
}

/**
 * The node types that can be placed and wired straight to [origin], one entry
 * per node type — the "drag off a port into empty space" palette.
 *
 * A candidate matches when it declares a port of the same [PortKind] and the
 * opposite [Direction]; DATA ports additionally have to satisfy
 * [isDataAssignable]. When several ports on the same node type match, the
 * typed one wins over a wildcard one and declaration order breaks the tie.
 *
 * Matching reads the *declared* [NodeTypeDefinition.ports], so node types whose
 * ports are derived from their connections
 * ([NodeTypeDefinition.hasDynamicPorts] — `action.break`, `action.condition`)
 * participate only through the ports they declare statically. `action.break`
 * declares its wildcard `struct` input, so it is offered for every data-output
 * drag; it declares no data outputs (they are derived from whatever is wired
 * into `struct`), so it cannot be offered for a drag from a data input — there
 * would be no port to connect to.
 */
fun suggestionsFor(
    origin: DragOrigin,
    candidates: List<NodeTypeDefinition> = NodeTypeRegistry.all,
): List<NodeSuggestion> {
    val wantDirection = if (origin.isOutput) Direction.IN else Direction.OUT
    val originPort = Port(
        name = ORIGIN_PORT_NAME,
        kind = origin.kind,
        direction = if (origin.isOutput) Direction.OUT else Direction.IN,
        schema = origin.schema,
    )
    return candidates.mapNotNull { definition ->
        definition.ports
            .filter { it.kind == origin.kind && it.direction == wantDirection && accepts(originPort, it, origin) }
            .minByOrNull { if (it.schema == ItemSchema.Wildcard) 1 else 0 }
            ?.let { NodeSuggestion(definition, it) }
    }
}

/**
 * Whether this node type answers to [term] in the palette's search box.
 *
 * Lives here rather than inline in the palette so it can be tested: what a node is
 * *called* is not the only way people look for it, and a node nobody can find is
 * exactly as useful as one that does not exist. The [description] is part of the
 * haystack for that reason — it is where a node's other names live, which is how
 * searching "loop" reaches three nodes all called "Repeat …".
 *
 * A blank term matches everything, so the caller can pass the box's contents
 * straight in.
 */
fun NodeTypeDefinition.matchesSearch(term: String): Boolean {
    val needle = term.trim()
    if (needle.isEmpty()) return true
    return listOf(displayName, description, typeId.value, category.displayName)
        .any { it.contains(needle, ignoreCase = true) }
}

private fun accepts(originPort: Port, candidate: Port, origin: DragOrigin): Boolean = when (origin.kind) {
    PortKind.EXECUTION -> true
    PortKind.DATA -> if (origin.isOutput) {
        isDataAssignable(source = originPort, target = candidate)
    } else {
        isDataAssignable(source = candidate, target = originPort)
    }
}

private val ORIGIN_PORT_NAME = PortName("origin")
