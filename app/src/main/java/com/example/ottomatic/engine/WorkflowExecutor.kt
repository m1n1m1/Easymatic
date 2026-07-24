package com.example.ottomatic.engine

import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.registry.ActionRegistry
import com.example.ottomatic.engine.trigger.TriggerOutput
import com.example.ottomatic.engine.validation.GraphValidator
import com.example.ottomatic.engine.validation.Severity

/**
 * Walks the workflow graph from a fired trigger and runs connected actions.
 *
 * Execution model:
 *  1. A trigger fires a [TriggerOutput]. Its [TriggerOutput.value] items are
 *     cached in [dataCache] keyed by `(nodeId, portName)`.
 *  2. The executor follows the trigger's EXECUTION `out` port, running each
 *     connected action once. Contract-encoded outputs are cached and followed.
 *  3. Failed actions log and stop their branch (matching the previous engine).
 *
 * Data semantics: a data edge's source must be exec-upstream of its target
 * (enforced by [GraphValidator]) so the source has run by the time the target
 * executes. An action receives a contract-decoded typed input. [collectDataIn] follows each
 * [com.example.ottomatic.domain.model.DataConnection]
 * into the target and reads the source port's cached item. An unwired data
 * input port simply yields no entry, and the action falls back to its static
 * config form value for the same key.
 */
class WorkflowExecutor(
    private val context: ExecutionContext,
) {

    suspend fun executeFrom(workflow: Workflow, triggerNode: WorkflowNode, output: TriggerOutput) {
        val issues = GraphValidator(workflow).validate()
        if (issues.any { it.severity == Severity.ERROR }) {
            issues.filter { it.severity == Severity.ERROR }.forEach { context.log("Workflow invalid: ${it.message}") }
            return
        }
        val dataCache = mutableMapOf<Pair<String, String>, Item>()
        output.value.forEach { (port, item) ->
            dataCache[triggerNode.id to port] = item
        }
        pulse(workflow, triggerNode, EXEC_OUT, dataCache)
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private suspend fun pulse(
        workflow: Workflow,
        node: WorkflowNode,
        port: String,
        dataCache: MutableMap<Pair<String, String>, Item>,
    ) {
        val outgoing = workflow.outgoingExec(node.id, port)
        for (connection in outgoing) {
            val target = workflow.node(connection.toNodeId) ?: continue
            val action = ActionRegistry.byId(target.typeId) ?: continue
            val dataIn = collectDataIn(workflow, target, dataCache)
            val input = NodeInput(target, dataIn)
            val result = runCatching { action.run(input, context) }.getOrElse { e ->
                context.log("Action ${target.typeId} failed: ${e.message}")
                null
            } ?: continue
            result.dataOut.forEach { (p, item) ->
                dataCache[target.id to p] = item
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
     * [target] and reading the source port's cached item from [dataCache].
     * Missing/unset sources are skipped (they produce no entry in the returned
     * map); the action then falls back to its static config value.
     */
    private fun collectDataIn(
        workflow: Workflow,
        target: WorkflowNode,
        dataCache: Map<Pair<String, String>, Item>,
    ): Map<String, Item> {
        val incoming = workflow.incomingData(target.id)
        if (incoming.isEmpty()) return emptyMap()
        val result = HashMap<String, Item>(incoming.size)
        for (conn in incoming) {
            val source = dataCache[conn.fromNodeId to conn.fromPort] ?: continue
            result[conn.toPort] = source
        }
        return result
    }

    companion object {
        /** Triggers pulse their single exec output port named `out`. */
        const val EXEC_OUT = "out"
    }
}
