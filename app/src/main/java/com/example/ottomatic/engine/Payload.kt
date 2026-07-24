package com.example.ottomatic.engine

import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.schema.Item

/**
 * Input handed to an [Action] when it runs.
 *
 * [config] gives typed access to the node's config fields, interpolating
 * EXPR fields against [dataContext] — a flat string view of every data item
 * produced upstream in the current execution chain (trigger + preceding
 * actions). This preserves the n8n-style `{{field}}` templating UX while the
 * typed data ports carry the structured items themselves.
 *
 * [dataIn] carries the typed [Item]s arriving on the action's DATA input
 * ports, keyed by input port name. Populated by [WorkflowExecutor] from its
 * data cache following [com.example.ottomatic.domain.model.DataConnection]s.
 * Empty when the action has no incoming data edges.
 */
data class ActionInput(
    val node: WorkflowNode,
    val config: TypedConfig,
    val dataContext: Map<String, String>,
    val dataIn: Map<String, Item> = emptyMap(),
)

/**
 * Result of running an [Action].
 *
 * [execOut] names the EXECUTION output ports that should pulse next
 * (e.g. `listOf("out")` for a linear action, `listOf("true")` or
 * `listOf("false")` for the condition node). [dataOut] maps DATA output
 * port names to the [Item]s the action produced; the executor caches them so
 * downstream EXPR interpolation and future typed data inputs can read them.
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
