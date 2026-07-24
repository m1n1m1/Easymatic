package com.example.ottomatic.engine

import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.schema.asTyped

/** Raw graph values available while a contract constructs a typed action input. */
class NodeInput internal constructor(
    val node: WorkflowNode,
    private val data: Map<String, Item>,
) {
    fun configString(key: String, default: String = ""): String =
        node.config[key]?.takeIf { it.isNotBlank() } ?: default

    fun configInt(key: String, default: Int = 0): Int = node.config[key]?.toIntOrNull() ?: default

    fun configBoolean(key: String, default: Boolean = false): Boolean =
        node.config[key]?.toBooleanStrictOrNull() ?: default

    fun item(port: String): Item? = data[port]

    /** Reads a String port, falling back to its paired static configuration field. */
    fun text(port: String, default: String = ""): String =
        data[port]?.asTyped<String>()?.takeIf { it.isNotEmpty() } ?: configString(port, default)
}

sealed interface ExecutionRoute {
    data object Out : ExecutionRoute
    data object True : ExecutionRoute
    data object False : ExecutionRoute
}

/** Typed result produced by an action or trigger before it is encoded for the graph runtime. */
data class NodeOutput<out T : Any>(
    val value: T,
    val route: ExecutionRoute = ExecutionRoute.Out,
    val halt: Boolean = false,
)

/** Internal executor representation. Port-name maps do not escape this contract boundary. */
class EncodedNodeOutput internal constructor(
    val execOut: List<String>,
    val dataOut: Map<String, Item>,
    val halt: Boolean,
)

/**
 * Defines how one action's graph values become its Kotlin input and output ports.
 * Constructed as part of the action's single [ActionNodeDefinition] via [actionNode].
 */
class ActionContract<I : Any, O : Any>(
    val typeId: String,
    private val decode: (NodeInput) -> I,
    private val encodeData: (O) -> Map<String, Item>,
    private val encodeRoute: (ExecutionRoute) -> List<String> = { listOf("out") },
) {
    fun input(values: NodeInput): I = decode(values)

    fun output(output: NodeOutput<O>): EncodedNodeOutput = EncodedNodeOutput(
        execOut = encodeRoute(output.route),
        dataOut = encodeData(output.value),
        halt = output.halt,
    )
}

/** Non-generic execution bridge used by the heterogeneous action registry. */
interface ExecutableAction {
    val definition: ActionNodeDefinition<*, *>

    val typeId: String get() = definition.typeId

    suspend fun run(input: NodeInput, context: ExecutionContext): EncodedNodeOutput
}

/**
 * An action receives and returns only its contract's Kotlin types. The generic
 * bridge keeps the graph's string port IDs at the executor boundary.
 *
 * The action's [definition] is the node's single declaration (metadata, ports,
 * config fields and contract); it lives in the action's own file and the
 * domain registries derive their views from it.
 */
interface Action<I : Any, O : Any> : ExecutableAction {
    override val definition: ActionNodeDefinition<I, O>

    val contract: ActionContract<I, O> get() = definition.contract

    suspend fun execute(input: I, context: ExecutionContext): NodeOutput<O>

    override suspend fun run(input: NodeInput, context: ExecutionContext): EncodedNodeOutput =
        contract.output(execute(contract.input(input), context))
}
