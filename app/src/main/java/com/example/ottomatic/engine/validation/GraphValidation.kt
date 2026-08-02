package com.example.ottomatic.engine.validation

import com.example.ottomatic.core.model.NodeId

/**
 * Everything [GraphValidator] found about one graph, in the shape both sides need.
 *
 * There is one *type* here, not one instance: the editor validates the live
 * in-memory graph so it can badge cards while they are being wired, and the
 * executor validates the disk snapshot `arm()` handed it. Those are legitimately
 * different graphs, and they must stay that way — feeding the editor's flow into
 * the engine would put `feature/` on the wrong side of the `engine ← domain +
 * core` rule and make a run depend on whether the editor happened to be open.
 *
 * The two halves of what it carries:
 *
 *  - **Attribution** ([issuesFor], [severityFor]) — what to badge, so a problem is
 *    visible on the canvas without running anything.
 *  - **Quarantine** ([blockedNodes], [blockedConnections]) — the union of what each
 *    finding says must not run. This is deliberately the *smallest* thing that is
 *    actually broken: one edge, or one node and whatever hangs off it. A workflow
 *    is not all-or-nothing, so a loop in one trigger's branch leaves every other
 *    trigger running.
 */
class GraphValidation(val issues: List<ValidationIssue>) {

    val errors: List<ValidationIssue> = issues.filter { it.severity == Severity.ERROR }

    val warnings: List<ValidationIssue> = issues.filter { it.severity == Severity.WARNING }

    val blockedNodes: Set<NodeId> = issues.flatMapTo(mutableSetOf()) { it.blockedNodes }

    val blockedConnections: Set<String> = issues.flatMapTo(mutableSetOf()) { it.blockedConnections }

    /**
     * Precomputed rather than scanned: [severityFor] is called once per node card
     * per recomposition, and a linear scan over the issue list would make laying
     * out the canvas quadratic in the number of nodes.
     */
    private val byNode: Map<NodeId, List<ValidationIssue>> =
        issues.flatMap { issue -> issue.nodes.map { it to issue } }
            .groupBy({ it.first }, { it.second })

    fun issuesFor(nodeId: NodeId): List<ValidationIssue> = byNode[nodeId].orEmpty()

    /** The worst thing said about [nodeId], or null if nothing was. */
    fun severityFor(nodeId: NodeId): Severity? =
        byNode[nodeId]?.minByOrNull { it.severity.ordinal }?.severity

    /** True when nothing at all is quarantined — the whole graph will run. */
    val isRunnable: Boolean get() = errors.isEmpty()

    val isEmpty: Boolean get() = issues.isEmpty()

    companion object {
        val EMPTY = GraphValidation(emptyList())
    }
}
