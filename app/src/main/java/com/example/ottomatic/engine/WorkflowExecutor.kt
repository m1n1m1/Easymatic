package com.example.ottomatic.engine

import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.registry.ActionRegistry
import com.example.ottomatic.engine.trigger.TriggerEvent
import com.example.ottomatic.engine.validation.GraphValidator
import com.example.ottomatic.engine.validation.Severity

/**
 * Walks the workflow graph from a fired trigger and runs connected actions.
 *
 * Execution model:
 *  1. A trigger fires a [TriggerEvent]. Its [TriggerEvent.dataOut] items are
 *     cached and flattened into the [dataContext] used for `{{field}}` EXPR
 *     interpolation.
 *  2. The executor follows the trigger's EXECUTION `out` port, running each
 *     connected action once. Each action's [ActionResult.dataOut] is cached
 *     and merged into [dataContext]; each [ActionResult.execOut] port is
 *     followed recursively.
 *  3. Failed actions log and stop their branch (matching the previous engine).
 *
 * Strict data semantics (enforced by [GraphValidator]): a data edge's source
 * must be exec-upstream of its target so the source has run by the time the
 * target executes. v1 actions read upstream data via the [dataContext] (EXPR
 * interpolation); typed data *input* ports will be wired in a later pass.
 */
class WorkflowExecutor(
    private val context: ExecutionContext,
) {

    suspend fun executeFrom(workflow: Workflow, triggerNode: WorkflowNode, event: TriggerEvent) {
        val issues = GraphValidator(workflow).validate()
        if (issues.any { it.severity == Severity.ERROR }) {
            issues.filter { it.severity == Severity.ERROR }.forEach { context.log("Workflow invalid: ${it.message}") }
            return
        }
        val dataCache = mutableMapOf<Pair<String, String>, Item>()
        val dataContext = mutableMapOf<String, String>()
        event.dataOut.forEach { (port, item) ->
            dataCache[triggerNode.id to port] = item
            mergeIntoContext(dataContext, port, item)
        }
        pulse(workflow, triggerNode, EXEC_OUT, dataCache, dataContext)
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private suspend fun pulse(
        workflow: Workflow,
        node: WorkflowNode,
        port: String,
        dataCache: MutableMap<Pair<String, String>, Item>,
        dataContext: MutableMap<String, String>,
    ) {
        val outgoing = workflow.outgoingExec(node.id, port)
        for (connection in outgoing) {
            val target = workflow.node(connection.toNodeId) ?: continue
            val action = ActionRegistry.byId(target.typeId) ?: continue
            val config = TypedConfig(target.config, dataContext.toMap())
            val input = ActionInput(target, config, dataContext.toMap())
            val result = runCatching { action.execute(input, context) }.getOrElse { e ->
                context.log("Action ${target.typeId} failed: ${e.message}")
                null
            } ?: continue
            result.dataOut.forEach { (p, item) ->
                dataCache[target.id to p] = item
                mergeIntoContext(dataContext, p, item)
            }
            for (execPort in result.execOut) {
                pulse(workflow, target, execPort, dataCache, dataContext)
            }
        }
    }

    private fun mergeIntoContext(ctx: MutableMap<String, String>, portName: String, item: Item) {
        item.flat.forEach { (k, v) ->
            ctx[k] = v
            ctx["$portName.$k"] = v
        }
        ctx[portName] = item.value?.toString() ?: ""
    }

    companion object {
        /** Triggers pulse their single exec output port named `out`. */
        const val EXEC_OUT = "out"
    }
}
