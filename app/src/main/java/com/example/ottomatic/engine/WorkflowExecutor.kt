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
 * target executes. Actions read upstream data in two complementary ways:
 *  - EXPR interpolation via the flat [dataContext] (`{{field}}` placeholders);
 *  - typed access via [ActionInput.dataIn] (the structured [Item]s on the
 *    action's DATA input ports, collected by [collectDataIn] from [dataCache]).
 *    Each exposed config field ([WorkflowNode.exposedInputs]) is such a port:
 *    its incoming item overrides the node's static form value for that field
 *    via [mergeConfig].
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
            val dataIn = collectDataIn(workflow, target, dataCache)
            val config = mergeConfig(target, dataIn, dataContext.toMap())
            val input = ActionInput(target, config, dataContext.toMap(), dataIn)
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

    /**
     * Builds the [TypedConfig] for [target]. Starts from the node's static
     * form config and, for each config field the user has exposed as a DATA
     * input ([WorkflowNode.exposedInputs]) that carries an incoming [Item],
     * overrides the corresponding config key with the item's string form. A
     * field exposed but not wired keeps its static form value, so the form
     * acts as the per-field default when no edge is connected.
     */
    @Suppress("UNCHECKED_CAST")
    private fun mergeConfig(
        target: WorkflowNode,
        dataIn: Map<String, Item>,
        dataContext: Map<String, String>,
    ): TypedConfig {
        val base = target.config
        if (target.exposedInputs.isEmpty()) return TypedConfig(base, dataContext)
        val merged = LinkedHashMap<String, String>(base.size + target.exposedInputs.size).apply { putAll(base) }
        for (key in target.exposedInputs) {
            val incoming = dataIn[key] ?: continue
            merged[key] = incoming.value?.toString() ?: ""
        }
        return TypedConfig(merged, dataContext)
    }

    /**
     * Collects the typed [Item]s arriving on [target]'s DATA input ports by
     * following each [DataConnection] into [target] and reading the source
     * port's cached item from [dataCache]. Missing/unset sources are skipped
     * (they produce no entry in the returned map).
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
