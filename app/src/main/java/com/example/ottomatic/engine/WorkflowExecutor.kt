package com.example.ottomatic.engine

import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.DataConnection
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.registry.ActionRegistry
import com.example.ottomatic.domain.registry.TransformRegistry
import com.example.ottomatic.domain.registry.ValueRegistry
import com.example.ottomatic.engine.trigger.TriggerOutput
import com.example.ottomatic.engine.validation.GraphValidator
import com.example.ottomatic.engine.validation.Severity

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

    suspend fun executeFrom(workflow: Workflow, triggerNode: WorkflowNode, output: TriggerOutput) {
        val issues = GraphValidator(workflow).validate()
        if (issues.any { it.severity == Severity.ERROR }) {
            issues.filter { it.severity == Severity.ERROR }.forEach { context.log("Workflow invalid: ${it.message}") }
            return
        }
        val dataCache: DataCache = mutableMapOf()
        output.value.forEach { (port, item) ->
            dataCache[triggerNode.id to port] = item
        }
        pulse(workflow, triggerNode, ExecutionRoute.OUT.portName, dataCache)
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private suspend fun pulse(
        workflow: Workflow,
        node: WorkflowNode,
        port: PortName,
        dataCache: DataCache,
    ) {
        val outgoing = workflow.outgoingExec(node.id, port)
        for (connection in outgoing) {
            val target = workflow.node(connection.toNodeId) ?: continue
            val action = ActionRegistry.byId(target.typeId) ?: continue
            val dataIn = collectDataIn(workflow, target, dataCache)
            val result = runCatching { action.run(target, dataIn, context) }.getOrElse { e ->
                context.log("Action ${target.typeId} failed: ${e.message}")
                null
            } ?: continue
            result.dataOut.forEach { (producedOn, item) ->
                dataCache[target.id to producedOn] = item
            }
            if (result.halt) {
                context.log("Action ${target.typeId} halted execution chain")
                return
            }
            for (execPort in result.execOut) {
                pulse(workflow, target, execPort, dataCache)
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
        target: WorkflowNode,
        dataCache: DataCache,
        reads: MutableMap<NodeId, Item?> = HashMap(),
        visiting: MutableSet<NodeId> = HashSet(),
    ): Map<PortName, Item> {
        val incoming = workflow.incomingData(target.id)
        if (incoming.isEmpty()) return emptyMap()
        val result = HashMap<PortName, Item>(incoming.size)
        for (conn in incoming) {
            val item = resolveDataIn(workflow, conn, target, dataCache, reads, visiting)
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
        conn: DataConnection,
        target: WorkflowNode,
        dataCache: DataCache,
        reads: MutableMap<NodeId, Item?>,
        visiting: MutableSet<NodeId>,
    ): Item? {
        val sourceNode = workflow.node(conn.fromNodeId) ?: return null
        if (conn.fromNodeId in reads) return reads[conn.fromNodeId]
        ValueRegistry.byId(sourceNode.typeId)?.let { value ->
            return readValue(value, sourceNode, target).also { reads[conn.fromNodeId] = it }
        }
        TransformRegistry.byId(sourceNode.typeId)?.let { transform ->
            return readTransform(workflow, transform, sourceNode, target, dataCache, reads, visiting)
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
        transform: ExecutableTransform,
        sourceNode: WorkflowNode,
        target: WorkflowNode,
        dataCache: DataCache,
        reads: MutableMap<NodeId, Item?>,
        visiting: MutableSet<NodeId>,
    ): Item? {
        if (!visiting.add(sourceNode.id)) {
            context.log("Transform ${transform.typeId.value} skipped: it depends on itself")
            return null
        }
        return try {
            val data = collectDataIn(workflow, sourceNode, dataCache, reads, visiting)
            val item = runCatching { transform.transformRaw(sourceNode, data, context) }.getOrElse { cause ->
                context.log("Transform ${transform.typeId.value} failed: ${cause.message}")
                null
            }
            if (item == null) {
                context.log("Transform ${transform.typeId.value} produced nothing for '${target.name}'")
            } else {
                context.log("Transform ${transform.typeId.value} = ${item.value} for '${target.name}'")
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
        value: ValueNode<*, *>,
        sourceNode: WorkflowNode,
        target: WorkflowNode,
    ): Item? {
        val item = runCatching { value.readRaw(sourceNode.config, context) }.getOrElse { cause ->
            context.log("Read ${value.typeId.value} failed: ${cause.message}")
            null
        }
        if (item == null) {
            context.log("Read ${value.typeId.value} unavailable for '${target.name}'")
        } else {
            context.log("Read ${value.typeId.value} = ${item.value} for '${target.name}'")
        }
        return item
    }
}
