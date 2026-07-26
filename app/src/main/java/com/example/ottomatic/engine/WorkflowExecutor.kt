package com.example.ottomatic.engine

import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.DataConnection
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.registry.ActionRegistry
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
 *  4. A node whose attached conditions do not pass ([conditionsPass]) is skipped
 *     along with everything below it.
 *
 * Data semantics: a data edge whose source is an *action or trigger* must be
 * exec-upstream of its target (enforced by [GraphValidator]) so the source has run
 * by the time the target executes. [collectDataIn] follows each
 * [com.example.ottomatic.domain.model.DataConnection] into the target and reads
 * the source port's cached item. An unwired data input port simply yields no
 * entry, and the action's config class falls back to that property's form value.
 *
 * A data edge whose source is a [com.example.ottomatic.engine.ValueNode] works the
 * other way round — pull, not push. A value node is never pulsed and has no exec
 * position at all; it is *read* while collecting its consumer's inputs. The rule is
 * one sentence: **a value is read just before the node that uses it.** Within one
 * consumer every port sees a single read (so two ports of the same node can never
 * disagree), while two different consumers each read fresh (so a value can never
 * go stale across a delay, or across a future loop body).
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
            // Conditions are gathered before the node runs and see its collected
            // inputs. Failing one skips the node *and* stops its branch: nothing
            // pulses, so nothing downstream is reached.
            if (!target.conditionsPass(dataIn, context)) {
                context.log("Skipped ${target.typeId}: conditions not met")
                continue
            }
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
    private suspend fun collectDataIn(
        workflow: Workflow,
        target: WorkflowNode,
        dataCache: DataCache,
    ): Map<PortName, Item> {
        val incoming = workflow.incomingData(target.id)
        if (incoming.isEmpty()) return emptyMap()
        val reads = HashMap<NodeId, Item?>()
        val result = HashMap<PortName, Item>(incoming.size)
        for (conn in incoming) {
            val item = resolveDataIn(workflow, conn, target, dataCache, reads)
            if (item != null) result[conn.toPort] = item
        }
        return result
    }

    /**
     * The item arriving over [conn]: a cached output for a pushed source, or a live
     * read for a value node — memoized in [reads] so [target] sees one consistent
     * value however many of its ports the same value node feeds.
     */
    @Suppress("ReturnCount") // Null-guards on the optional node/cache path are idiomatic here.
    private suspend fun resolveDataIn(
        workflow: Workflow,
        conn: DataConnection,
        target: WorkflowNode,
        dataCache: DataCache,
        reads: MutableMap<NodeId, Item?>,
    ): Item? {
        val sourceNode = workflow.node(conn.fromNodeId) ?: return null
        val value = ValueRegistry.byId(sourceNode.typeId)
            ?: return dataCache[conn.fromNodeId to conn.fromPort]
        if (conn.fromNodeId in reads) return reads[conn.fromNodeId]
        return readValue(value, sourceNode, target).also { reads[conn.fromNodeId] = it }
    }

    /**
     * Reads [value] for [target], logging the outcome.
     *
     * A pulled value has no node-by-node line in the run log of its own, so without
     * this a failing gate or a surprising notification would be undiagnosable.
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
