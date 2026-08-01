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
import com.example.ottomatic.engine.validation.GraphValidator
import com.example.ottomatic.engine.validation.Severity
import java.util.concurrent.atomic.AtomicLong

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
     * Runs the graph once, under a fresh run id.
     *
     * A run *is* one call of this method, which is why the id is minted here and
     * threaded down rather than held on the executor: with `action.delay` in the
     * palette and one coroutine per trigger flow, two runs of the same workflow
     * genuinely overlap, and their lines interleave in the console.
     */
    suspend fun executeFrom(workflow: Workflow, triggerNode: WorkflowNode, output: TriggerOutput) {
        val runId = runIds.incrementAndGet()
        val run = context.scoped(source(workflow, runId, triggerNode))
        val issues = GraphValidator(workflow).validate()
        if (issues.any { it.severity == Severity.ERROR }) {
            issues.filter { it.severity == Severity.ERROR }
                .forEach { run.log("Workflow invalid: ${it.message}", LogLevel.ERROR) }
            return
        }
        run.log("Triggered by '${triggerNode.name}'")
        // What the trigger actually delivered. Everything downstream is derived
        // from it, so a run that surprises you is very often wrong right here.
        logData(run, OUT_LABEL, output.value)
        val dataCache: DataCache = mutableMapOf()
        output.value.forEach { (port, item) ->
            dataCache[triggerNode.id to port] = item
        }
        pulse(workflow, runId, triggerNode, ExecutionRoute.OUT.portName, dataCache)
        run.log("Run finished", LogLevel.DEBUG)
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private suspend fun pulse(
        workflow: Workflow,
        runId: Long,
        node: WorkflowNode,
        port: PortName,
        dataCache: DataCache,
    ) {
        val outgoing = workflow.outgoingExec(node.id, port)
        for (connection in outgoing) {
            val target = workflow.node(connection.toNodeId) ?: continue
            val action = ActionRegistry.byId(target.typeId) ?: continue
            val dataIn = collectDataIn(workflow, runId, target, dataCache)
            // Scoping here is what gives every `context.log(…)` written inside an
            // action body its node, without a single action knowing about it —
            // an action never receives its own WorkflowNode, only this does.
            val at = context.scoped(source(workflow, runId, target))
            at.log("→ ${target.name}", LogLevel.DEBUG)
            logData(at, IN_LABEL, dataIn)
            val result = runCatching { action.run(target, dataIn, at) }.getOrElse { e ->
                at.log("Action ${target.typeId} failed: ${e.message}", LogLevel.ERROR)
                null
            } ?: continue
            logData(at, OUT_LABEL, result.dataOut)
            result.dataOut.forEach { (producedOn, item) ->
                dataCache[target.id to producedOn] = item
            }
            if (result.halt) {
                at.log("Action ${target.typeId} halted execution chain")
                return
            }
            for (execPort in result.execOut) {
                pulse(workflow, runId, target, execPort, dataCache)
            }
        }
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
    @Suppress("LongParameterList") // The pull memo and cycle guard travel with the recursion.
    private suspend fun collectDataIn(
        workflow: Workflow,
        runId: Long,
        target: WorkflowNode,
        dataCache: DataCache,
        reads: MutableMap<NodeId, Item?> = HashMap(),
        visiting: MutableSet<NodeId> = HashSet(),
    ): Map<PortName, Item> {
        val incoming = workflow.incomingData(target.id)
        if (incoming.isEmpty()) return emptyMap()
        val result = HashMap<PortName, Item>(incoming.size)
        for (conn in incoming) {
            val item = resolveDataIn(workflow, runId, conn, target, dataCache, reads, visiting)
            if (item != null) result[conn.toPort] = item
        }
        return result
    }

    /**
     * The item arriving over [conn]: a cached output for a pushed source, or a live
     * pull for a value node or a transform — memoized in [reads] so [target] sees
     * one consistent value however many of its ports the same source feeds, whether
     * directly or through different transforms.
     */
    @Suppress("ReturnCount", "LongParameterList") // Null-guards on the optional node/cache path are idiomatic here.
    private suspend fun resolveDataIn(
        workflow: Workflow,
        runId: Long,
        conn: DataConnection,
        target: WorkflowNode,
        dataCache: DataCache,
        reads: MutableMap<NodeId, Item?>,
        visiting: MutableSet<NodeId>,
    ): Item? {
        val sourceNode = workflow.node(conn.fromNodeId) ?: return null
        if (conn.fromNodeId in reads) return reads[conn.fromNodeId]
        ValueRegistry.byId(sourceNode.typeId)?.let { value ->
            return readValue(workflow, runId, value, sourceNode, target).also { reads[conn.fromNodeId] = it }
        }
        TransformRegistry.byId(sourceNode.typeId)?.let { transform ->
            return readTransform(workflow, runId, transform, sourceNode, target, dataCache, reads, visiting)
                .also { reads[conn.fromNodeId] = it }
        }
        return dataCache[conn.fromNodeId to conn.fromPort]
    }

    /**
     * Pulls [transform] for [target], first pulling whatever *it* depends on.
     *
     * The recursion shares [reads], which is what keeps the "one consistent read per
     * consumer" rule honest across a chain: a value node feeding two transforms that
     * both feed [target] is still read exactly once.
     *
     * [visiting] guards against a data cycle. [GraphValidator] rejects those before
     * anything runs, so this only stops a hand-edited workflow file from recursing
     * until the stack gives out.
     */
    @Suppress("LongParameterList") // The pull memo and cycle guard travel with the recursion.
    private suspend fun readTransform(
        workflow: Workflow,
        runId: Long,
        transform: ExecutableTransform,
        sourceNode: WorkflowNode,
        target: WorkflowNode,
        dataCache: DataCache,
        reads: MutableMap<NodeId, Item?>,
        visiting: MutableSet<NodeId>,
    ): Item? {
        // Attributed to the transform, not to whoever pulled it: the line is
        // about that node, and it is the one the console should select.
        val at = context.scoped(source(workflow, runId, sourceNode))
        if (!visiting.add(sourceNode.id)) {
            at.log("Transform ${transform.typeId.value} skipped: it depends on itself", LogLevel.ERROR)
            return null
        }
        return try {
            val data = collectDataIn(workflow, runId, sourceNode, dataCache, reads, visiting)
            logData(at, IN_LABEL, data)
            val item = runCatching { transform.transformRaw(sourceNode, data, at) }.getOrElse { cause ->
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
        workflow: Workflow,
        runId: Long,
        value: ValueNode<*, *>,
        sourceNode: WorkflowNode,
        target: WorkflowNode,
    ): Item? {
        val at = context.scoped(source(workflow, runId, sourceNode))
        val item = runCatching { value.readRaw(sourceNode.config, at) }.getOrElse { cause ->
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
         * arm, so a per-instance counter would restart on every re-arm.
         */
        val runIds = AtomicLong()
    }
}
