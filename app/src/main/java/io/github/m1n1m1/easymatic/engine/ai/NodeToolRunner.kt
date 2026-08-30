package io.github.m1n1m1.easymatic.engine.ai

import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.core.service.AiToolCall
import io.github.m1n1m1.easymatic.core.service.AiToolResult
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.domain.model.NodeKind
import io.github.m1n1m1.easymatic.domain.model.ToolTarget
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.schema.Item
import io.github.m1n1m1.easymatic.domain.model.schema.asText
import io.github.m1n1m1.easymatic.domain.registry.ActionRegistry
import io.github.m1n1m1.easymatic.domain.registry.ValueRegistry
import io.github.m1n1m1.easymatic.engine.ConditionalLoopAction
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.ForkAction
import io.github.m1n1m1.easymatic.engine.LoopAction

/**
 * Runs one tool the model asked for.
 *
 * **Nothing new is needed to invoke a node.** `ExecutableAction.run(node, data,
 * context)` already takes a config map and an execution context and touches the graph
 * nowhere; `ValueNode.readRaw(config, context)` needs no node at all. So a tool call
 * is a synthetic [WorkflowNode] carrying the merged config, and the same code path a
 * placed node takes.
 *
 * **Nothing throws out of here.** A tool that fails answers an [AiToolResult] with
 * its error flag set, and the model gets to try something else — which is the whole
 * reason that flag exists rather than an exception ending the run. An unknown tool
 * name, a node that has since been removed, a macro that is switched off and a
 * genuine crash inside an action all arrive at the model as one shape.
 */
class NodeToolRunner(
    private val tools: List<NodeTool>,
    private val context: ExecutionContext,
) {

    suspend fun invoke(call: AiToolCall): AiToolResult {
        val entry = tools.firstOrNull { it.tool.name == call.name }
            ?: return AiToolResult("There is no tool called \"${call.name}\"", isError = true)
        // The author's pinned values win over the model's arguments, always. Without
        // that a pinned light could be overridden by naming it again, which would
        // undo the one decision the tool list exists to make.
        val config = (call.arguments.mapKeys { ConfigKey(it.key) } + entry.spec.pinned)
        context.log("AI used ${call.name}${describe(config)}", LogLevel.INFO)
        val result = runCatching { dispatch(entry, config) }.getOrElse { error ->
            if (error is kotlin.coroutines.cancellation.CancellationException) throw error
            AiToolResult("The tool failed: ${error.message}", isError = true)
        }
        context.log("AI tool ${call.name} answered: ${result.text}", LogLevel.DEBUG)
        return result
    }

    private suspend fun dispatch(entry: NodeTool, config: Map<ConfigKey, String>): AiToolResult =
        when (val target = entry.spec.target) {
            is ToolTarget.Node -> runNode(target.typeId, config)
            is ToolTarget.Macro -> runMacro(target.macroId, config)
        }

    /** A value read, or an action run — decided by which registry knows the type. */
    @Suppress("ReturnCount") // Each exit is a different thing to tell the model; folding them loses that.
    private suspend fun runNode(typeId: NodeTypeId, config: Map<ConfigKey, String>): AiToolResult {
        ValueRegistry.byId(typeId)?.let { value ->
            val item = value.readRaw(config, context)
                ?: return AiToolResult("Nothing could be read", isError = true)
            return AiToolResult(item.asText())
        }
        val action = ActionRegistry.byId(typeId)
            ?: return AiToolResult("That node is no longer part of this app", isError = true)
        val node = WorkflowNode(
            id = NodeId(SYNTHETIC_NODE_ID),
            typeId = typeId,
            name = typeId.value,
            x = 0f,
            y = 0f,
            config = config,
        )
        return when (val attempt = AiToolDepth.nested { action.run(node, emptyMap(), context) }) {
            is AiToolDepth.Attempt.Done ->
                AiToolResult(render(attempt.value.dataOut, halted = attempt.value.halt))
            else -> AiToolResult(TOO_DEEP, isError = true)
        }
    }

    /**
     * Another macro, run through its `trigger.api` node and waited for.
     *
     * Two guards, and the second is the one nothing else provides: a macro that is
     * already running as a tool cannot be started again, so a macro whose agent node
     * can reach itself is stopped at the first attempt rather than at the depth limit
     * — by which point it would have run twice.
     */
    private suspend fun runMacro(macroId: String, config: Map<ConfigKey, String>): AiToolResult {
        val control = context.macroControl
            ?: return AiToolResult("Running another macro is not available here", isError = true)
        val inputs = config.mapKeys { it.key.value }
        return when (val attempt = AiToolDepth.nestedMacro(macroId) { control.run(macroId, inputs) }) {
            AiToolDepth.Attempt.TooDeep -> AiToolResult(TOO_DEEP, isError = true)
            AiToolDepth.Attempt.Busy -> AiToolResult(ALREADY_RUNNING, isError = true)
            is AiToolDepth.Attempt.Done -> when {
                attempt.value.ran -> AiToolResult("Done")
                else -> AiToolResult(attempt.value.error.ifBlank { "The macro did not run" }, isError = true)
            }
        }
    }

    /**
     * What an action produced, as text.
     *
     * A node with no data output is the ordinary case — most actions are effects — and
     * "Done" is the honest answer for one, not a placeholder. [halted] is reported
     * because a `Stop Macro` reached through a tool is a thing the model should know
     * it caused.
     */
    private fun render(dataOut: Map<io.github.m1n1m1.easymatic.core.model.PortName, Item>, halted: Boolean): String {
        val body = when {
            dataOut.isEmpty() -> "Done"
            dataOut.size == 1 -> dataOut.values.single().asText()
            else -> dataOut.entries.joinToString(separator = "\n") { (port, item) ->
                "${port.value}: ${item.asText()}"
            }
        }
        val whole = if (halted) "$body\n(this stopped the macro)" else body
        return if (whole.length <= MAX_RESULT_LENGTH) {
            whole
        } else {
            whole.take(MAX_RESULT_LENGTH) + "\n… (cut off after $MAX_RESULT_LENGTH characters)"
        }
    }

    /** The arguments, for the run log. This line is the audit trail for an unattended run. */
    private fun describe(config: Map<ConfigKey, String>): String =
        if (config.isEmpty()) {
            ""
        } else {
            config.entries.joinToString(prefix = "(", postfix = ")") { (key, value) ->
                "${key.value}=${value.take(MAX_LOGGED_VALUE)}"
            }
        }

    private companion object {
        const val SYNTHETIC_NODE_ID = "ai-tool"
        const val TOO_DEEP = "This is already running inside an AI tool call and cannot go deeper"
        const val ALREADY_RUNNING = "That macro is already running"

        /**
         * How much of a tool's answer the model is shown.
         *
         * A file listing or an HTTP body can be far larger than the question, and it
         * is sent again on every subsequent turn — so an uncapped result is paid for
         * repeatedly. Cut rather than dropped, and said so, because half of a
         * directory listing is usually still the answer.
         */
        const val MAX_RESULT_LENGTH = 4_000

        const val MAX_LOGGED_VALUE = 80
    }
}

/**
 * Whether a node type can be offered as a tool at all.
 *
 * Shared with [NodeToolCatalog] so a tool that cannot run is never *offered*, rather
 * than being offered and then refused — a model that spends a turn discovering this
 * has been billed for the app's own bookkeeping.
 *
 * Four exclusions, each for its own reason:
 *
 * - **Triggers** are not run; they fire.
 * - **Transforms** are pure functions of their *data inputs*, which arrive on wires a
 *   tool call has none of. One would receive nothing and answer accordingly.
 * - **The two loops** are driven by the executor itself. Their inherited `run` is a
 *   stub returning `completed` with no data, so offering one would produce a tool
 *   that silently did nothing at all — the worst failure available here.
 * - **A fork**, for the same reason and with one way out of it: the exclusion is
 *   about the stub rather than about being a fork, so one that overrides `run` and
 *   says so through [ForkAction.runsWithoutAFork] is offered. `action.notify` is the
 *   case — posting the notification is the whole job until somebody reacts, and
 *   reacting is not something a tool call could carry back anyway.
 * - **`action.if` and `action.break`** exist only relative to a placed graph: the
 *   first is exec routing with nothing to return, and the second's output ports are
 *   retyped from whatever is wired above it. A third node of that kind would need
 *   naming here too, which is the honest cost of the list being explicit.
 */
fun canRunAsTool(typeId: NodeTypeId, kind: NodeKind): Boolean = when (kind) {
    NodeKind.TRIGGER, NodeKind.TRANSFORM -> false
    NodeKind.VALUE -> true
    NodeKind.ACTION -> when (val action = ActionRegistry.byId(typeId)) {
        null -> false
        is LoopAction<*>, is ConditionalLoopAction<*> -> false
        is ForkAction<*> -> action.runsWithoutAFork && typeId.value !in GRAPH_SHAPED_ACTIONS
        else -> typeId.value !in GRAPH_SHAPED_ACTIONS
    }
}

private val GRAPH_SHAPED_ACTIONS = setOf("action.if", "action.break")
