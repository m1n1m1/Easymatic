package com.example.ottomatic.engine

import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.schema.Item

/**
 * Input handed to an [Action] when it runs.
 *
 * [config] gives typed access to the node's static form fields (literals the
 * user typed in the configure form — never data).
 *
 * [dataIn] carries the typed [Item]s arriving on the action's DATA input
 * ports, keyed by input port name. Populated by [WorkflowExecutor] from its
 * data cache following [com.example.ottomatic.domain.model.DataConnection]s.
 * Empty when the action has no incoming data edges. Each port that can be
 * wired is declared on the node type in
 * [com.example.ottomatic.domain.registry.NodeTypeRegistry].
 *
 * Use [string] to read a value that may be either wired from upstream data or
 * set as a static form literal: it returns the wired item's string form when
 * the port is connected, otherwise the static [config] value for [key].
 */
data class ActionInput(
    val node: WorkflowNode,
    val config: TypedConfig,
    val dataIn: Map<String, Item> = emptyMap(),
) {

    /**
     * Reads the string form of the item arriving on the DATA input port named
     * [key]; when no edge is wired to that port, falls back to the static
     * [config] value for [key] (or [default] when blank/missing).
     *
     * This is the single way to read a value that is "either wired or typed":
     * the action knows nothing about the producer — only the name of its own
     * input port, which matches the config field key.
     */
    fun string(key: String, default: String = ""): String =
        dataIn[key]?.value?.toString()?.takeIf { it.isNotEmpty() } ?: config.str(key, default)
}

/**
 * Result of running an [Action].
 *
 * [execOut] names the EXECUTION output ports that should pulse next
 * (e.g. `listOf("out")` for a linear action, `listOf("true")` or
 * `listOf("false")` for the condition node). [dataOut] maps DATA output
 * port names to the [Item]s the action produced; the executor caches them so
 * downstream data inputs can read them.
 */
data class ActionResult(
    val execOut: List<String> = emptyList(),
    val dataOut: Map<String, Item> = emptyMap(),
    /**
     * When `true`, the [com.example.ottomatic.engine.WorkflowExecutor] stops
     * traversing the current execution chain after running this action: no
     * further `execOut` ports are followed. Used by `action.stop` and any
     * future short-circuiting action. Data produced in [dataOut] is still
     * cached before the halt so already-running sibling branches can read it.
     */
    val halt: Boolean = false,
) {
    companion object {
        /** Pulses [port] (default `"out"`) and produces no data. */
        fun passthrough(port: String = "out"): ActionResult = ActionResult(execOut = listOf(port))
    }
}

/**
 * A unit of executable behaviour behind a node whose
 * [com.example.ottomatic.domain.model.NodeTypeDefinition] has
 * [com.example.ottomatic.domain.model.NodeKind.ACTION].
 *
 * Implementations live in `engine/action/` and are registered in
 * [com.example.ottomatic.domain.registry.ActionRegistry].
 */
interface Action {

    val typeId: String

    suspend fun execute(input: ActionInput, context: ExecutionContext): ActionResult
}
