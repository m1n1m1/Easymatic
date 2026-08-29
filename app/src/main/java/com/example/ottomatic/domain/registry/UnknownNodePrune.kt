package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.nodeapi.plugin.PluginLimits

/**
 * The graph with every node whose type this build no longer declares removed, along
 * with the edges that reached one.
 *
 * `GraphValidator` names such a node and quarantines it, which is the right answer
 * for a node the user can *see* — but nothing draws one. `GraphCanvas` resolves a
 * definition before it lays a card out and skips the node when it cannot, so an
 * unknown node is an error the Problems panel reports about something that is not on
 * the canvas: unselectable, and therefore undeletable. The macro is then permanently
 * badged with a fault the editor offers no way to fix. Dropping the node is the only
 * repair available, and it costs nothing that was still working — a node whose type
 * is gone could not have run either.
 *
 * **A plugin's node is never dropped**, whatever the registry currently says, and that
 * is the whole subtlety here rather than a caveat on it. `PluginNodes` is empty before
 * the first discovery pass finishes, empty again while a plugin is merely *disabled*,
 * and empty for an uninstalled one — three states that are indistinguishable from
 * `NodeTypeRegistry` and only one of which is permanent. Two of them are undone by a
 * switch in Settings or by a reinstall, and `pluginPackageOf` exists precisely because
 * the typeId still says which app to go and get. So the test is the *prefix*, not
 * hydration: a `plugin:` node stays, and `GraphValidator` goes on saying why it cannot
 * run. What this drops is the case with no way back — a first-party typeId that the
 * compiled build simply does not have any more.
 *
 * Idempotent, and pure: the caller decides whether the result is written back. See
 * `WorkflowRepository.repaired` for why it is not.
 */
fun pruneUnknownNodes(workflow: Workflow): UnknownNodePruneResult {
    val doomed = workflow.nodes.filter { isForgotten(it.typeId) }
    if (doomed.isEmpty()) return UnknownNodePruneResult(workflow, emptyList())

    val ids: Set<NodeId> = doomed.mapTo(mutableSetOf()) { it.id }
    return UnknownNodePruneResult(
        workflow = workflow.copy(
            nodes = workflow.nodes.filterNot { it.id in ids },
            execConnections = workflow.execConnections
                .filterNot { it.fromNodeId in ids || it.toNodeId in ids },
            dataConnections = workflow.dataConnections
                .filterNot { it.fromNodeId in ids || it.toNodeId in ids },
        ),
        removed = doomed.map { it.typeId },
    )
}

/** True only for a type nothing can bring back — see [pruneUnknownNodes]. */
private fun isForgotten(typeId: NodeTypeId): Boolean =
    !PluginLimits.isPluginTypeId(typeId.value) && NodeTypeRegistry.byId(typeId) == null

/** [pruneUnknownNodes]' answer: the surviving graph, and what it dropped. */
data class UnknownNodePruneResult(
    val workflow: Workflow,
    /** The typeIds dropped, one entry per node, in graph order. Empty when nothing was. */
    val removed: List<NodeTypeId>,
) {
    val changed: Boolean get() = removed.isNotEmpty()
}
