package com.example.ottomatic.feature.grapheditor

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.domain.model.ApiTokens
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.registry.API_TOKEN_KEY
import com.example.ottomatic.domain.registry.API_TRIGGER_TYPE_ID
import java.util.UUID

/**
 * How far a copy lands from its original, in graph units.
 *
 * Against a node's 72 dp height and 190 dp minimum width ([GraphGeometry]) this is a
 * clear step down-right that still overlaps: the copy reads as *a second card on top
 * of the first* rather than as an unrelated node placed elsewhere, which is what says
 * "this came from that" before the user has moved anything.
 */
const val DUPLICATE_OFFSET = 24f

/** What [withDuplicated] produced: the graph with the copies in it, and the copies. */
data class Duplication(val workflow: Workflow, val selection: Selection)

/**
 * Copies every selected node — **and the wires between them** — offset down-right.
 *
 * The sibling of [withoutSelection], and the same kind of function for the same
 * reason: a pure `Workflow -> Workflow` step that junit can reach, since the project
 * carries no coroutines-test or Robolectric and a `GraphEditorViewModel` therefore
 * cannot be instantiated in a unit test at all (see `SelectionReducer.kt`).
 *
 * An edge is copied **only when both of its endpoints are selected**, following
 * Unreal Blueprints and n8n: duplicating a working sub-graph should give a working
 * sub-graph, while an edge crossing the selection boundary has only one end to
 * attach to and would either dangle or silently fan the original's upstream into a
 * second consumer. Neither is what the user drew a box around.
 *
 * Selected *connections* are ignored entirely. An edge has no existence apart from
 * the two nodes it joins, so there is nothing for a copy of one to be — which makes
 * an edges-only selection duplicate nothing and return this graph unchanged.
 *
 * The three factories are parameters rather than calls so the whole thing stays
 * testable: a test injects counters and asserts exact ids, where `UUID.randomUUID()`
 * would leave nothing to assert against.
 */
fun Workflow.withDuplicated(
    selection: Selection,
    newNodeId: () -> NodeId = { NodeId(UUID.randomUUID().toString()) },
    newEdgeId: () -> String = { UUID.randomUUID().toString() },
    freshConfig: (WorkflowNode) -> Map<ConfigKey, String> = ::reissuedConfig,
): Duplication {
    val originals = nodes.filter { it.id in selection.nodeIds }
    if (originals.isEmpty()) return Duplication(this, selection)

    // Built in one pass and then read twice — once to place the copies, once to
    // repoint the edges — so a node and the edges touching it cannot disagree
    // about which new id it got.
    val remapped: Map<NodeId, WorkflowNode> = originals.associate { original ->
        original.id to original.copy(
            id = newNodeId(),
            x = original.x + DUPLICATE_OFFSET,
            y = original.y + DUPLICATE_OFFSET,
            config = freshConfig(original),
        )
    }

    val copiedExec = execConnections.mapNotNull { edge ->
        val from = remapped[edge.fromNodeId] ?: return@mapNotNull null
        val to = remapped[edge.toNodeId] ?: return@mapNotNull null
        edge.copy(id = newEdgeId(), fromNodeId = from.id, toNodeId = to.id)
    }
    val copiedData = dataConnections.mapNotNull { edge ->
        val from = remapped[edge.fromNodeId] ?: return@mapNotNull null
        val to = remapped[edge.toNodeId] ?: return@mapNotNull null
        edge.copy(id = newEdgeId(), fromNodeId = from.id, toNodeId = to.id)
    }

    val copies = remapped.values
    return Duplication(
        workflow = copy(
            nodes = nodes + copies,
            execConnections = execConnections + copiedExec,
            dataConnections = dataConnections + copiedData,
        ),
        // The copies become the selection, so the drag that almost always follows
        // moves them off the originals rather than moving the originals again.
        selection = Selection(nodeIds = copies.mapTo(mutableSetOf()) { it.id }),
    )
}

/**
 * [node]'s config as a *new* node should carry it: verbatim, except for the values
 * that are only ever minted and never copied.
 *
 * There is exactly one of those today. A `trigger.api` node's token is a bearer
 * credential — `ApiTokens`' own KDoc calls it "the only credential the Intent front
 * door has", because a broadcast arrives carrying no sender identity whatsoever.
 * Copying it would leave two independently callable triggers behind one secret, so
 * rotating one would not rotate the other and a caller aiming at one would
 * authenticate against both.
 *
 * That is also just what the rest of the editor already does. `initialConfig` mints a
 * token for every `trigger.api` however it was placed, on the argument that a node
 * must not "work or not depending on how it was made" — and a duplicate is a node
 * being made. The price is that a duplicated trigger needs its new key copied into
 * whatever calls it, which is correct: it is a different endpoint.
 */
fun reissuedConfig(node: WorkflowNode): Map<ConfigKey, String> = when (node.typeId) {
    API_TRIGGER_TYPE_ID -> node.config + (API_TOKEN_KEY to ApiTokens.generate())
    else -> node.config
}
