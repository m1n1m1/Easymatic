package com.example.ottomatic.engine

import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.PortName
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
 *     cached in the [DataCache] keyed by node id and port name.
 *  2. The executor follows the trigger's EXECUTION `out` port, running each
 *     connected action once. Encoded outputs are cached and followed.
 *  3. Failed actions log and stop their branch.
 *  4. A node whose attached conditions do not pass ([conditionsPass]) is skipped
 *     along with everything below it.
 *
 * Data semantics: a data edge's source must be exec-upstream of its target
 * (enforced by [GraphValidator]) so the source has run by the time the target
 * executes. [collectDataIn] follows each
 * [com.example.ottomatic.domain.model.DataConnection] into the target and reads
 * the source port's cached item. An unwired data input port simply yields no
 * entry, and the action's config class falls back to that property's form value.
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
     * [target] and reading the source port's cached item. Missing sources are
     * skipped (they produce no entry), and the action's config class then falls
     * back to the form value of the corresponding property.
     */
    private fun collectDataIn(
        workflow: Workflow,
        target: WorkflowNode,
        dataCache: DataCache,
    ): Map<PortName, Item> {
        val incoming = workflow.incomingData(target.id)
        if (incoming.isEmpty()) return emptyMap()
        val result = HashMap<PortName, Item>(incoming.size)
        for (conn in incoming) {
            val source = dataCache[conn.fromNodeId to conn.fromPort] ?: continue
            result[conn.toPort] = source
        }
        return result
    }
}
