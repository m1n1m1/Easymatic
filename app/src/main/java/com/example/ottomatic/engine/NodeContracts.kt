package com.example.ottomatic.engine

import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.execOut
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.model.ExecPorts
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.schema.Item

/**
 * The EXECUTION output a node pulses after running. A node declares which
 * routes it may take via [ExecOutputs]; the port name is derived from the route,
 * so no node maps routes onto port-name strings any more.
 */
enum class ExecutionRoute(val portName: PortName) {
    OUT(ExecPorts.OUT),
    TRUE(ExecPorts.TRUE),
    FALSE(ExecPorts.FALSE),
}

/** The set of EXECUTION output ports a node exposes. */
enum class ExecOutputs(val routes: List<ExecutionRoute>) {
    /** A single `out` port: the node always continues along one path. */
    SINGLE(listOf(ExecutionRoute.OUT)),

    /** `true` / `false` ports: the node routes conditionally. */
    BRANCH(listOf(ExecutionRoute.TRUE, ExecutionRoute.FALSE)),
    ;

    val ports: List<Port> get() = routes.map { execOut(it.portName) }
}

/** Typed result produced by a node before it is encoded for the graph runtime. */
data class NodeOutput<out T : Any>(
    val value: T,
    val route: ExecutionRoute = ExecutionRoute.OUT,
    val halt: Boolean = false,
)

/** Internal executor representation. Port-name maps do not escape this boundary. */
class EncodedNodeOutput internal constructor(
    val execOut: List<PortName>,
    val dataOut: Map<PortName, Item>,
    val halt: Boolean,
)

/**
 * Raw graph values for a placed node. Only the two adaptive nodes
 * ([RawAction]) see this: every other node receives a typed config object
 * decoded from its declared config class.
 */
class NodeInput internal constructor(
    val node: WorkflowNode,
    private val data: Map<PortName, Item>,
) {
    /** The item wired into [port], or null when the port is unwired. */
    fun item(port: PortName): Item? = data[port]

    /** The wired value of [port] as text, or null when unwired or empty. */
    fun text(port: PortName): String? = data[port]?.value?.toString()?.takeIf { it.isNotEmpty() }
}

/** Non-generic execution bridge used by the heterogeneous action registry. */
interface ExecutableAction {
    val definition: ActionNodeDefinition<*, *>

    val typeId: NodeTypeId get() = definition.typeId

    suspend fun run(node: WorkflowNode, data: Map<PortName, Item>, context: ExecutionContext): EncodedNodeOutput
}

/**
 * An action receives its declared config type [I] and returns its declared
 * output type [O] — nothing else. The graph's string port names and config keys
 * live entirely in the node's [definition], which derives them from [I] and
 * from its output port declaration.
 */
interface Action<I : Any, O : Any> : ExecutableAction {
    override val definition: ActionNodeDefinition<I, O>

    suspend fun execute(input: I, context: ExecutionContext): NodeOutput<O>

    override suspend fun run(
        node: WorkflowNode,
        data: Map<PortName, Item>,
        context: ExecutionContext,
    ): EncodedNodeOutput = definition.encode(execute(definition.schema.decode(node, data), context))
}

/**
 * Escape hatch for the two *adaptive* actions (`action.break`,
 * `action.condition`), whose data ports are not statically known: their output
 * ports are derived from the schema of whatever struct is connected
 * ([com.example.ottomatic.domain.registry.effectivePorts]), so they emit a
 * port-keyed map directly and read their wildcard inputs as raw [Item]s.
 *
 * They still receive their non-wildcard configuration as a typed [C], so the
 * only untyped surface left in the node system is the port-keyed map these two
 * nodes must produce by definition.
 */
interface RawAction<C : Any> : ExecutableAction {
    override val definition: ActionNodeDefinition<C, Unit>

    suspend fun executeRaw(
        config: C,
        input: NodeInput,
        context: ExecutionContext,
    ): NodeOutput<Map<PortName, Item>>

    override suspend fun run(
        node: WorkflowNode,
        data: Map<PortName, Item>,
        context: ExecutionContext,
    ): EncodedNodeOutput = definition.encodeDynamic(
        executeRaw(definition.schema.decode(node, data), NodeInput(node, data), context),
    )
}
