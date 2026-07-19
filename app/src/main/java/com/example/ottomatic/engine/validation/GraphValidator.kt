package com.example.ottomatic.engine.validation

import com.example.ottomatic.domain.model.DataConnection
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.ExecConnection
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.domain.registry.CONDITION_SOURCE_IN
import com.example.ottomatic.domain.registry.CONDITION_TYPE_ID
import com.example.ottomatic.domain.registry.NodeTypeRegistry
import com.example.ottomatic.domain.registry.effectivePort

/**
 * One finding produced by validating a [Workflow] graph.
 *
 * [ERROR]-severity issues block execution; [WARNING]s are reported but do not.
 * [location] is a human-readable hint (e.g. a connection id or "nodeId/port").
 */
data class ValidationIssue(
    val severity: Severity,
    val message: String,
    val location: String? = null,
)

enum class Severity { ERROR, WARNING }

/**
 * Validates a [Workflow] graph against the two-channel (execution + data)
 * model: port existence and kind/direction sanity, acyclicity of both edge
 * sets, structural schema subtyping on data edges, and the strict data
 * semantics rule (a data edge's source must be exec-upstream of its target).
 *
 * Run on save in the editor and before [com.example.ottomatic.engine.WorkflowExecutor] runs.
 */
@Suppress("TooManyFunctions")
class GraphValidator(private val workflow: Workflow) {

    fun validate(): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        validateExecConnections(issues)
        validateDataConnections(issues)
        validateExecAcyclicity(issues)
        validateDataAcyclicity(issues)
        validateStrictDataSemantics(issues)
        validateConditionSourceInput(issues)
        return issues
    }

    /** True iff there are no [Severity.ERROR] issues. */
    fun isValid(): Boolean = validate().none { it.severity == Severity.ERROR }

    private fun validateExecConnections(out: MutableList<ValidationIssue>) {
        for (conn in workflow.execConnections) {
            val (from, to) = resolveExec(conn) ?: run {
                out += ValidationIssue(Severity.ERROR, "Unknown node in exec connection", conn.id)
                continue
            }
            if (from == null) {
                out += ValidationIssue(
                    Severity.ERROR,
                    "Unknown exec output port '${conn.fromPort}' on ${conn.fromNodeId}",
                    conn.id,
                )
            }
            if (to == null) {
                out += ValidationIssue(
                    Severity.ERROR,
                    "Unknown exec input port '${conn.toPort}' on ${conn.toNodeId}",
                    conn.id,
                )
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    private fun validateDataConnections(out: MutableList<ValidationIssue>) {
        for (conn in workflow.dataConnections) {
            val fromNode = workflow.node(conn.fromNodeId)
            val toNode = workflow.node(conn.toNodeId)
            if (fromNode == null || toNode == null) {
                out += ValidationIssue(Severity.ERROR, "Unknown node in data connection", conn.id)
                continue
            }
            val fromDef = NodeTypeRegistry.byId(fromNode.typeId)
            val toDef = NodeTypeRegistry.byId(toNode.typeId)
            if (fromDef == null || toDef == null) {
                out += ValidationIssue(Severity.ERROR, "Unknown node type in data connection", conn.id)
                continue
            }
            // Always resolve via effectivePort: any action may have gained
            // exposed-config DATA input ports (see WorkflowNode.exposedInputs),
            // and `action.break` derives its field output ports dynamically.
            val fromPort = effectivePort(fromDef, workflow, fromNode, conn.fromPort, Direction.OUT)
                ?: fromDef.port(conn.fromPort)
            val toPort = effectivePort(toDef, workflow, toNode, conn.toPort, Direction.IN)
                ?: toDef.port(conn.toPort)
            val fromBad = fromPort == null || fromPort.kind != PortKind.DATA || fromPort.direction != Direction.OUT
            if (fromBad) {
                out += ValidationIssue(
                    Severity.ERROR,
                    "'${conn.fromPort}' is not a data output port on ${conn.fromNodeId}",
                    conn.id,
                )
                continue
            }
            val toBad = toPort == null || toPort.kind != PortKind.DATA || toPort.direction != Direction.IN
            if (toBad) {
                out += ValidationIssue(
                    Severity.ERROR,
                    "'${conn.toPort}' is not a data input port on ${conn.toNodeId}",
                    conn.id,
                )
                continue
            }
            val sourceSchema = fromPort.schema ?: ItemSchema.Wildcard
            val targetSchema = toPort.schema ?: ItemSchema.Wildcard
            if (!targetSchema.isAssignableFrom(sourceSchema)) {
                out += ValidationIssue(
                    Severity.ERROR,
                    "Schema mismatch on data edge: source $sourceSchema not assignable to target $targetSchema",
                    conn.id,
                )
            }
        }
    }

    private fun validateExecAcyclicity(out: MutableList<ValidationIssue>) {
        val cycle = findCycle(workflow.execConnections) { it.fromNodeId to it.toNodeId }
        if (cycle != null) {
            out += ValidationIssue(Severity.ERROR, "Execution cycle detected: ${cycle.joinToString(" -> ")}")
        }
    }

    private fun validateDataAcyclicity(out: MutableList<ValidationIssue>) {
        val cycle = findCycle(workflow.dataConnections) { it.fromNodeId to it.toNodeId }
        if (cycle != null) {
            out += ValidationIssue(Severity.ERROR, "Data cycle detected: ${cycle.joinToString(" -> ")}")
        }
    }

    @Suppress("ReturnCount")
    private fun validateStrictDataSemantics(out: MutableList<ValidationIssue>) {
        // For every data edge source -> target, source must be exec-upstream of target
        // (i.e. target is reachable from source by following exec edges). Otherwise the
        // source would not have run by the time the target executes.
        val execForward = mutableMapOf<String, MutableList<String>>()
        workflow.execConnections.forEach {
            execForward.getOrPut(it.fromNodeId) { mutableListOf() } += it.toNodeId
        }
        for (conn in workflow.dataConnections) {
            if (!reaches(execForward, conn.fromNodeId, conn.toNodeId)) {
                out += ValidationIssue(
                    Severity.ERROR,
                    "Data source ${conn.fromNodeId} is not exec-upstream of ${conn.toNodeId}" +
                        ": the source will not have run when the target executes",
                    conn.id,
                )
            }
        }
    }

    @Suppress("ReturnCount")
    private fun reaches(
        execForward: Map<String, List<String>>,
        start: String,
        target: String,
    ): Boolean {
        if (start == target) return true
        val seen = mutableSetOf<String>()
        val stack = ArrayDeque(execForward[start] ?: emptyList())
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            if (cur == target) return true
            if (seen.add(cur)) execForward[cur]?.forEach { stack.addLast(it) }
        }
        return false
    }

    private fun resolveExec(conn: ExecConnection): Pair<Any?, Any?> {
        val fromNode = workflow.node(conn.fromNodeId)
        val toNode = workflow.node(conn.toNodeId)
        val fromDef = fromNode?.let { NodeTypeRegistry.byId(it.typeId) }
        val toDef = toNode?.let { NodeTypeRegistry.byId(it.typeId) }
        val fromPort = fromDef?.port(conn.fromPort)
            ?.takeIf { it.kind == PortKind.EXECUTION && it.direction == Direction.OUT }
        val toPort = toDef?.port(conn.toPort)
            ?.takeIf { it.kind == PortKind.EXECUTION && it.direction == Direction.IN }
        return fromPort to toPort
    }

    private fun <E> findCycle(edges: List<E>, endpoints: (E) -> Pair<String, String>): List<String>? {
        val adj = mutableMapOf<String, MutableList<String>>()
        edges.forEach { e ->
            val (from, to) = endpoints(e)
            adj.getOrPut(from) { mutableListOf() } += to
            adj.getOrPut(to) { mutableListOf() }
        }
        val visited = mutableSetOf<String>()
        val onStack = mutableSetOf<String>()
        val path = mutableListOf<String>()
        fun dfs(node: String): List<String>? {
            visited += node
            onStack += node
            path += node
            for (next in adj[node] ?: emptyList()) {
                if (next !in visited) {
                    dfs(next)?.let { return@dfs it }
                } else if (next in onStack) {
                    val cycleStart = path.indexOf(next)
                    return path.subList(cycleStart, path.size) + next
                }
            }
            onStack -= node
            path.removeAt(path.lastIndex)
            return null
        }
        for (start in adj.keys) if (start !in visited) dfs(start)?.let { return it }
        return null
    }

    @Suppress("unused")
    private fun kindLabel(kind: NodeKind) = if (kind == NodeKind.TRIGGER) "trigger" else "action"

    @Suppress("unused")
    private fun DataConnection.describe(): String = "$fromNodeId.$fromPort -> $toNodeId.$toPort"

    /**
     * `action.condition`'s `source` input is an exposable config field. When
     * the user has exposed it (so it is a DATA IN port) but no data edge is
     * wired into it, the comparison has no typed target — flag it so the user
     * knows to wire an edge (or un-expose it to use the literal form value).
     */
    private fun validateConditionSourceInput(out: MutableList<ValidationIssue>) {
        for (node in workflow.nodes.filter { it.typeId == CONDITION_TYPE_ID }) {
            if (CONDITION_SOURCE_IN !in node.exposedInputs) continue
            val incoming = workflow.incomingData(node.id, CONDITION_SOURCE_IN)
            if (incoming.isEmpty()) {
                out += ValidationIssue(
                    Severity.ERROR,
                    "Condition node '${node.name}' has no data edge into its " +
                        "'$CONDITION_SOURCE_IN' port; connect a data source or un-expose " +
                        "'Source' to use the literal form value.",
                    node.id,
                )
            }
        }
    }
}
