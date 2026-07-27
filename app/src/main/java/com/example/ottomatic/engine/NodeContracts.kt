package com.example.ottomatic.engine

import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.execOut
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.model.ExecPorts
import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.schema.asText

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
    fun text(port: PortName): String? = data[port]?.asText()?.takeIf { it.isNotEmpty() }
}

/**
 * A pure reader of something that is true *right now* — the graph's pull side.
 *
 * A value node performs no work and changes nothing, which is what lets it be read
 * without an execution position: wired into a consumer's DATA input it is read just
 * before that consumer runs, and named as an `action.if` source it is read when the
 * comparison evaluates. Both paths go through [readRaw], so a value can never mean
 * one thing wired and another named.
 *
 * [read] returns null when the value cannot be read at all (subsystem absent,
 * permission not granted), mirroring [com.example.ottomatic.core.service.DeviceState].
 * A null read contributes no item, so the consumer falls back to its own form
 * value exactly as it would for an unwired port.
 */
interface ValueNode<C : Any, O : Any> {
    val definition: ValueNodeDefinition<C, O>

    val typeId: NodeTypeId get() = definition.typeId

    /** The value right now, or null when it cannot be read. */
    suspend fun read(config: C, context: ExecutionContext): O?

    /**
     * Reads from a raw [config] map — the entry point for both placements.
     *
     * There is no `data` parameter: a value node is a leaf, so it has no inputs to
     * resolve. The declaration contract test enforces that.
     */
    suspend fun readRaw(
        config: Map<ConfigKey, String>,
        context: ExecutionContext,
    ): Item? = read(definition.schema.decode(config), context)?.let { definition.encode(it) }
}

/**
 * Non-generic pull bridge used by the heterogeneous transform registry.
 *
 * A transform is a pure *function* of its data inputs — the other half of the
 * graph's pull side. Where a [ValueNode] is a leaf reading of something true right
 * now, a transform derives its single output from data it is given, so pulling one
 * pulls whatever it depends on (see
 * [com.example.ottomatic.engine.WorkflowExecutor]). Like a value node it has no
 * EXECUTION ports and is never pulsed, and returning null means "unreadable": it
 * contributes no item, and the consumer falls back to its own form value.
 *
 * Purity is a contract, not a convention: `NodeDeclarationContractTest` asserts
 * that every transform declares no exec ports, no permissions, at least one DATA
 * input and exactly one DATA output. Anything failable or side-effecting is an
 * action.
 */
interface ExecutableTransform {
    val definition: TransformNodeDefinition<*, *>

    val typeId: NodeTypeId get() = definition.typeId

    suspend fun transformRaw(
        node: WorkflowNode,
        data: Map<PortName, Item>,
        context: ExecutionContext,
    ): Item?
}

/**
 * A transform whose output type [O] is fixed at declaration time. It receives its
 * declared config type [C] — including anything wired into a `@Wired` property —
 * and returns [O], exactly as [Action] does.
 */
interface TransformNode<C : Any, O : Any> : ExecutableTransform {
    override val definition: TransformNodeDefinition<C, O>

    /** The derived value, or null when it cannot be produced. */
    suspend fun transform(config: C, context: ExecutionContext): O?

    override suspend fun transformRaw(
        node: WorkflowNode,
        data: Map<PortName, Item>,
        context: ExecutionContext,
    ): Item? = transform(definition.schema.decode(node, data), context)?.let { definition.encode(it) }
}

/**
 * Escape hatch for a transform whose output *schema* is chosen in its own config
 * (`transform.convert`, `transform.json_read`) and so cannot be a static type
 * parameter. It emits an [Item] directly and may read a wildcard input as a raw
 * [Item] — the same trade [RawAction] makes, for the same reason.
 */
interface RawTransform<C : Any> : ExecutableTransform {
    override val definition: TransformNodeDefinition<C, Unit>

    suspend fun transformItem(config: C, input: NodeInput, context: ExecutionContext): Item?

    override suspend fun transformRaw(
        node: WorkflowNode,
        data: Map<PortName, Item>,
        context: ExecutionContext,
    ): Item? = transformItem(definition.schema.decode(node, data), NodeInput(node, data), context)
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
 * `action.if`), whose data ports are not statically known: their output
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
