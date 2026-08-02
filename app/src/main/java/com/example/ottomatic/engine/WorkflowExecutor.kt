package com.example.ottomatic.engine

import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.LogSource
import com.example.ottomatic.domain.model.DataConnection
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.schema.asText
import com.example.ottomatic.domain.registry.ActionRegistry
import com.example.ottomatic.domain.registry.TransformRegistry
import com.example.ottomatic.domain.registry.ValueRegistry
import com.example.ottomatic.engine.trigger.TriggerOutput
import com.example.ottomatic.engine.validation.GraphValidation
import com.example.ottomatic.engine.validation.GraphValidator
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/**
 * Walks the workflow graph from a fired trigger and runs connected actions.
 *
 * Execution model:
 *  1. A trigger fires a [TriggerOutput]. Its [TriggerOutput.value] items are
 *     cached in the [DataCache] keyed by node id and port name.
 *  2. The executor follows the trigger's EXECUTION `out` port, running each
 *     connected action once. Encoded outputs are cached and followed.
 *  3. Failed actions log and stop their branch.
 *
 * **A problem stops the smallest thing it can.** [GraphValidator] runs once per
 * run and names, per finding, the edges and nodes that must not run; everything
 * else does. So a loop wired into one trigger's branch costs that one wire, and
 * every sibling branch — and every other trigger in the same workflow — still
 * runs. The alternative, refusing the whole graph, made one bad edge look
 * identical to a macro that had never been armed.
 *
 * Data semantics: a data edge whose source is an *action or trigger* must be
 * exec-upstream of its target (enforced by [GraphValidator]) so the source has run
 * by the time the target executes. [collectDataIn] follows each
 * [com.example.ottomatic.domain.model.DataConnection] into the target and reads
 * the source port's cached item. An unwired data input port simply yields no
 * entry, and the action's config class falls back to that property's form value.
 *
 * A data edge whose source is a [com.example.ottomatic.engine.ValueNode] or an
 * [ExecutableTransform] works the other way round — pull, not push. Neither is ever
 * pulsed and neither has an exec position at all; both are *read* while collecting
 * their consumer's inputs. The rule is one sentence: **a value is read just before
 * the node that uses it.** Within one consumer every port sees a single read (so two
 * ports of the same node can never disagree), while two different consumers each read
 * fresh (so a value can never go stale across a delay, or across a future loop body).
 *
 * A transform extends that rule rather than bending it: pulling one first pulls
 * whatever feeds it, so a whole chain of conversions resolves in one go, sharing a
 * single memo — a value node reaching one consumer through two different transforms
 * is still read once.
 */
/** Items produced so far, addressed by the port they were produced on. */
private typealias DataCache = MutableMap<Pair<NodeId, PortName>, Item>

class WorkflowExecutor(
    private val context: ExecutionContext,
) {

    /**
     * Everything one run carries with it.
     *
     * Threaded rather than held as a field, for the same reason [runId] is minted
     * per call: with `action.delay` in the palette and one coroutine per trigger
     * flow, two runs of the same workflow genuinely overlap. A field would have them
     * share a data cache and a cycle guard, and the interleaving would be invisible.
     */
    private class Run(
        val workflow: Workflow,
        val runId: Long,
        val validation: GraphValidation,
        val dataCache: DataCache = mutableMapOf(),
        /**
         * The nodes on the exec path currently being walked — **not** the nodes that
         * have run. See [pulse].
         */
        val onPath: MutableSet<NodeId> = HashSet(),
    )

    /**
     * Runs the graph once, under a fresh run id.
     *
     * A run *is* one call of this method, which is why the id is minted here and
     * threaded down rather than held on the executor.
     *
     * Problems are announced as a **single summary line**, not one line per finding.
     * A macro firing every minute against a graph with one bad edge would otherwise
     * write that inventory into the console every minute and evict the trace that
     * explains what actually happened — and since the run now continues, that trace
     * is the interesting part. The full list belongs in the editor's Problems panel,
     * where it does not repeat; what lands here is only what a given run tripped over.
     */
    suspend fun executeFrom(workflow: Workflow, triggerNode: WorkflowNode, output: TriggerOutput) {
        val runId = runIds.incrementAndGet()
        val at = context.scoped(source(workflow, runId, triggerNode))
        at.log("Triggered by '${triggerNode.name}'")
        val validation = GraphValidator(workflow).validate()
        if (validation.errors.isNotEmpty()) {
            at.log("${validation.errors.size} problem(s) in this workflow; affected steps are skipped", LogLevel.ERROR)
        }
        if (triggerNode.id in validation.blockedNodes) {
            at.log("'${triggerNode.name}' cannot run until its problem is fixed", LogLevel.ERROR)
            return
        }
        // What the trigger actually delivered. Everything downstream is derived
        // from it, so a run that surprises you is very often wrong right here.
        logData(at, OUT_LABEL, output.value)
        val run = Run(workflow, runId, validation)
        output.value.forEach { (port, item) ->
            run.dataCache[triggerNode.id to port] = item
        }
        run.onPath += triggerNode.id
        pulse(run, triggerNode, ExecutionRoute.OUT.portName)
        at.log("Run finished", LogLevel.DEBUG)
    }

    /**
     * Follows one exec output port, running each node it reaches.
     *
     * [Run.onPath] is a **path-scoped** guard, added before a node runs and removed
     * in a `finally` on the way back out — the same shape as the `visiting` set in
     * [readTransform]. It must not be a global visited set: a diamond (T→A, T→B,
     * A→J, B→J) is supposed to run J once per incoming pulse, and a visited set
     * would silently swallow the second. What it does stop is a true loop, in a
     * graph that reached the engine hand-edited or armed before the cycle rule
     * existed — where the previous version recursed until the stack gave out.
     */
    @Suppress("LoopWithTooManyJumpStatements")
    private suspend fun pulse(run: Run, node: WorkflowNode, port: PortName) {
        val outgoing = run.workflow.outgoingExec(node.id, port)
        for (connection in outgoing) {
            val target = run.workflow.node(connection.toNodeId) ?: continue
            // Scoping here is what gives every `context.log(…)` written inside an
            // action body its node, without a single action knowing about it —
            // an action never receives its own WorkflowNode, only this does.
            val at = context.scoped(source(run.workflow, run.runId, target))
            val blocked = blockedReason(run, connection, node, target)
            if (blocked != null) {
                at.log(blocked, LogLevel.ERROR)
                continue
            }
            val action = ActionRegistry.byId(target.typeId) ?: continue
            if (!run.onPath.add(target.id)) {
                at.log("Execution cycle: '${target.name}' is already running on this path", LogLevel.ERROR)
                continue
            }
            try {
                if (!runNode(run, action, target, at)) return
            } finally {
                run.onPath.remove(target.id)
            }
        }
    }

    /**
     * Why [target] must not be reached over [connection], or null when it may be.
     *
     * Attributed to [target] rather than to [from]: the interesting fact is which
     * step did not happen, and that is the card the console's tap-to-select should
     * land on.
     */
    private fun blockedReason(
        run: Run,
        connection: com.example.ottomatic.domain.model.ExecConnection,
        from: WorkflowNode,
        target: WorkflowNode,
    ): String? = when {
        connection.id in run.validation.blockedConnections ->
            "Not reached from '${from.name}': that wire has a problem"
        target.id in run.validation.blockedNodes ->
            "Skipped: this step has a problem that must be fixed first"
        else -> null
    }

    /**
     * Runs one node and follows its exec outputs. Returns false when the chain
     * halted, which unwinds this branch of the walk.
     */
    @Suppress("ReturnCount") // "kept walking" has three distinct ways of being decided.
    private suspend fun runNode(
        run: Run,
        action: ExecutableAction,
        target: WorkflowNode,
        at: ExecutionContext,
    ): Boolean {
        val dataIn = collectDataIn(run, target)
        at.log("→ ${target.name}", LogLevel.DEBUG)
        logData(at, IN_LABEL, dataIn)
        val result = runCatching { action.run(target, dataIn, at) }.getOrElse { e ->
            // A cancelled run is not a failed action: swallowing this logged a
            // bogus failure and then carried on walking a graph nobody was waiting for.
            if (e is CancellationException) throw e
            at.log("Action ${target.typeId} failed: ${e.message}", LogLevel.ERROR)
            null
        } ?: return true
        logData(at, OUT_LABEL, result.dataOut)
        result.dataOut.forEach { (producedOn, item) ->
            run.dataCache[target.id to producedOn] = item
        }
        if (result.halt) {
            at.log("Action ${target.typeId} halted execution chain")
            return false
        }
        for (execPort in result.execOut) {
            pulse(run, target, execPort)
        }
        return true
    }

    /**
     * Collects the typed [Item]s arriving on [target]'s DATA input ports by
     * following each [com.example.ottomatic.domain.model.DataConnection] into
     * [target]. An edge from an action or trigger reads that source port's cached
     * item; an edge from a value node *reads the value now*. Missing sources are
     * skipped (they produce no entry), and the action's config class then falls
     * back to the form value of the corresponding property.
     *
     * [reads] is deliberately a local: it memoizes each value node for the duration
     * of this one call, which is exactly the "fresh per consumer" rule — consistent
     * across [target]'s own ports, re-read for the next consumer.
     */
    private suspend fun collectDataIn(
        run: Run,
        target: WorkflowNode,
        reads: MutableMap<NodeId, Item?> = HashMap(),
        visiting: MutableSet<NodeId> = HashSet(),
    ): Map<PortName, Item> {
        val incoming = run.workflow.incomingData(target.id)
        if (incoming.isEmpty()) return emptyMap()
        val result = HashMap<PortName, Item>(incoming.size)
        for (conn in incoming) {
            val item = resolveDataIn(run, conn, target, reads, visiting)
            if (item != null) result[conn.toPort] = item
        }
        return result
    }

    /**
     * The item arriving over [conn]: a cached output for a pushed source, or a live
     * pull for a value node or a transform — memoized in [reads] so [target] sees
     * one consistent value however many of its ports the same source feeds, whether
     * directly or through different transforms.
     *
     * A quarantined edge carries nothing. In the ordinary case the node reading it
     * has already been held back by [pulse], so this never comes up; it matters for
     * the one case where the validator could not name a consumer — a transform chain
     * ending nowhere — and it costs one set lookup to be sure.
     */
    @Suppress("ReturnCount", "LongParameterList") // Null-guards on the optional node/cache path are idiomatic here.
    private suspend fun resolveDataIn(
        run: Run,
        conn: DataConnection,
        target: WorkflowNode,
        reads: MutableMap<NodeId, Item?>,
        visiting: MutableSet<NodeId>,
    ): Item? {
        if (conn.id in run.validation.blockedConnections) return null
        val sourceNode = run.workflow.node(conn.fromNodeId) ?: return null
        if (conn.fromNodeId in reads) return reads[conn.fromNodeId]
        ValueRegistry.byId(sourceNode.typeId)?.let { value ->
            return readValue(run, value, sourceNode, target).also { reads[conn.fromNodeId] = it }
        }
        TransformRegistry.byId(sourceNode.typeId)?.let { transform ->
            return readTransform(run, transform, sourceNode, target, reads, visiting)
                .also { reads[conn.fromNodeId] = it }
        }
        return run.dataCache[conn.fromNodeId to conn.fromPort]
    }

    /**
     * Pulls [transform] for [target], first pulling whatever *it* depends on.
     *
     * The recursion shares [reads], which is what keeps the "one consistent read per
     * consumer" rule honest across a chain: a value node feeding two transforms that
     * both feed [target] is still read exactly once.
     *
     * [visiting] guards against a data cycle. [GraphValidator] reports those and
     * quarantines the edge that closes them, so this only stops a hand-edited
     * workflow file from recursing until the stack gives out.
     */
    @Suppress("LongParameterList") // The pull memo and cycle guard travel with the recursion.
    private suspend fun readTransform(
        run: Run,
        transform: ExecutableTransform,
        sourceNode: WorkflowNode,
        target: WorkflowNode,
        reads: MutableMap<NodeId, Item?>,
        visiting: MutableSet<NodeId>,
    ): Item? {
        // Attributed to the transform, not to whoever pulled it: the line is
        // about that node, and it is the one the console should select.
        val at = context.scoped(source(run.workflow, run.runId, sourceNode))
        if (!visiting.add(sourceNode.id)) {
            at.log("Transform ${transform.typeId.value} skipped: it depends on itself", LogLevel.ERROR)
            return null
        }
        return try {
            val data = collectDataIn(run, sourceNode, reads, visiting)
            logData(at, IN_LABEL, data)
            val item = runCatching { transform.transformRaw(sourceNode, data, at) }.getOrElse { cause ->
                if (cause is CancellationException) throw cause
                at.log("Transform ${transform.typeId.value} failed: ${cause.message}", LogLevel.ERROR)
                null
            }
            if (item == null) {
                at.log("Transform ${transform.typeId.value} produced nothing for '${target.name}'", LogLevel.WARN)
            } else {
                at.log("Transform ${transform.typeId.value} = ${item.value} for '${target.name}'", LogLevel.DEBUG)
            }
            item
        } finally {
            visiting.remove(sourceNode.id)
        }
    }

    /**
     * Reads [value] for [target], logging the outcome.
     *
     * A pulled value has no node-by-node line in the run log of its own, so without
     * this a failing comparison or a surprising notification would be undiagnosable.
     */
    private suspend fun readValue(
        run: Run,
        value: ValueNode<*, *>,
        sourceNode: WorkflowNode,
        target: WorkflowNode,
    ): Item? {
        val at = context.scoped(source(run.workflow, run.runId, sourceNode))
        val item = runCatching { value.readRaw(sourceNode.config, at) }.getOrElse { cause ->
            if (cause is CancellationException) throw cause
            at.log("Read ${value.typeId.value} failed: ${cause.message}", LogLevel.ERROR)
            null
        }
        if (item == null) {
            at.log("Read ${value.typeId.value} unavailable for '${target.name}'", LogLevel.WARN)
        } else {
            at.log("Read ${value.typeId.value} = ${item.value} for '${target.name}'", LogLevel.DEBUG)
        }
        return item
    }

    private fun source(workflow: Workflow, runId: Long, node: WorkflowNode) =
        LogSource(workflow.id, runId, node.id.value, node.name)

    /**
     * The data crossing a node, as one line per direction.
     *
     * Knowing *that* a node ran only answers half the question; the other half is
     * always "with what?". This is where a wire that silently carried nothing, a
     * number that arrived as text, or a JSON path that matched the wrong field
     * becomes visible — none of which is deducible from the outcome alone.
     *
     * Only what came down a *wire* appears. A field typed into the form is not
     * here, because it is already on the card: the console's job is the part of a
     * node's input that is invisible until it runs.
     *
     * Silent when there is nothing, so an effect node with no data does not pay a
     * line saying so.
     */
    private fun logData(context: ExecutionContext, label: String, data: Map<PortName, Item>) {
        if (data.isEmpty()) return
        val rendered = data.entries.joinToString(separator = SEPARATOR) { (port, item) ->
            "${port.value} = ${preview(item)}"
        }
        context.log("$label $rendered", LogLevel.DEBUG)
    }

    private companion object {

        const val IN_LABEL = "in "
        const val OUT_LABEL = "out"
        const val SEPARATOR = "  ·  "

        /**
         * Enough of a value to recognise it, and never more.
         *
         * An `HttpResponseItem` body runs to megabytes. Held untruncated in a
         * 500-entry buffer per workflow, one polling macro would exhaust the heap
         * — so the cut happens here, on the way in, rather than in the console
         * that displays it.
         */
        const val MAX_VALUE_CHARS = 200

        /**
         * [Item.asText] is the renderer the rest of the app already agrees on —
         * primitives plainly, structs as compact JSON — so a value reads in the
         * console exactly as it would in a notification, and it never throws.
         */
        fun preview(item: Item): String {
            val text = item.asText()
            return when {
                text.isEmpty() -> "(empty)"
                text.length <= MAX_VALUE_CHARS -> text
                else -> text.take(MAX_VALUE_CHARS) + "… (${text.length} chars)"
            }
        }

        /**
         * Process-wide, because a run has to be distinguishable from every other
         * one showing up in the same console — and one executor is built per
         * arm, so a per-instance counter would restart on every re-arm. Counts up
         * from 1, which is what leaves [LogSource.NO_RUN] free for arm-time lines.
         */
        val runIds = AtomicLong()
    }
}
