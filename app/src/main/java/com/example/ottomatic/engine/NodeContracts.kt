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
import java.util.concurrent.atomic.AtomicInteger

/**
 * The EXECUTION output a node pulses after running. A node declares which
 * routes it may take via [ExecOutputs]; the port name is derived from the route,
 * so no node maps routes onto port-name strings any more.
 */
/**
 * [label] is what the port says when the card reveals it. The first three are named
 * well enough by their own port name — an `out`, a `true` and a `false` beside an
 * "If" need no gloss — but a loop's two outputs do: "body" and "completed" are
 * words from a programming language, and which of the two to wire is the single
 * thing people get wrong about a loop.
 */
enum class ExecutionRoute(val portName: PortName, val label: String) {
    OUT(ExecPorts.OUT, ExecPorts.OUT.value),
    TRUE(ExecPorts.TRUE, ExecPorts.TRUE.value),
    FALSE(ExecPorts.FALSE, ExecPorts.FALSE.value),
    BODY(ExecPorts.BODY, ExecPorts.BODY_LABEL),
    COMPLETED(ExecPorts.COMPLETED, ExecPorts.COMPLETED_LABEL),
    CONFIRMED(ExecPorts.CONFIRMED, ExecPorts.CONFIRMED_LABEL),
    CANCELLED(ExecPorts.CANCELLED, ExecPorts.CANCELLED_LABEL),
    TIMED_OUT(ExecPorts.TIMED_OUT, ExecPorts.TIMED_OUT_LABEL),

    /**
     * A fork's immediate branch. Deliberately the **same port** as [OUT] — a fork
     * carries on exactly where a plain action would, so a saved graph wired to
     * `out` keeps working if a node ever grows a second branch — and differs only
     * in what the card calls it. See [ExecPorts.CONTINUE_LABEL].
     */
    CONTINUE(ExecPorts.OUT, ExecPorts.CONTINUE_LABEL),

    /** A fork's deferred branch, pulsed when the awaited moment arrives. */
    RESUMED(ExecPorts.RESUMED, ExecPorts.RESUMED_LABEL),
}

/** The set of EXECUTION output ports a node exposes. */
enum class ExecOutputs(val routes: List<ExecutionRoute>) {
    /** A single `out` port: the node always continues along one path. */
    SINGLE(listOf(ExecutionRoute.OUT)),

    /** `true` / `false` ports: the node routes conditionally. */
    BRANCH(listOf(ExecutionRoute.TRUE, ExecutionRoute.FALSE)),

    /**
     * `body` / `completed` ports: the node repeats. Both are *forward* outputs —
     * nothing is ever wired back into the loop — so the graph stays acyclic and
     * [com.example.ottomatic.engine.validation.GraphValidator] needs no exception.
     * See [LoopAction].
     */
    LOOP(listOf(ExecutionRoute.BODY, ExecutionRoute.COMPLETED)),

    /**
     * `out` / `timed_out`: a dialog with nothing to decide — it was seen, or it
     * was not. The plain `out` is deliberately first and deliberately unrenamed:
     * acknowledging a message is the ordinary continuation every other action has.
     */
    ACKNOWLEDGED(listOf(ExecutionRoute.OUT, ExecutionRoute.TIMED_OUT)),

    /**
     * `confirmed` / `cancelled` / `timed_out`: a question put to the user.
     *
     * The third route is declared here rather than being folded into `cancelled`
     * because "the user said no" and "nobody was there" send a macro to two
     * different places. It is still *shown* only when a timeout is configured —
     * see `dialogEffectivePorts` — so the card carries no branch that cannot fire.
     */
    DECISION(listOf(ExecutionRoute.CONFIRMED, ExecutionRoute.CANCELLED, ExecutionRoute.TIMED_OUT)),

    /**
     * `out` / `resumed`: the node carries on at once **and** again later. Both are
     * *forward* outputs — nothing is wired back — so the graph stays acyclic and
     * neither [com.example.ottomatic.engine.validation.GraphValidator]'s cycle
     * rule nor the executor's `onPath` guard needs an exception carved into it,
     * exactly as for [LOOP].
     *
     * The first port is the ordinary `out`, following [ACKNOWLEDGED]'s precedent:
     * a fork is wired in where a plain action was, and only what the card *calls*
     * the port changes. See [ForkAction].
     */
    FORK(listOf(ExecutionRoute.CONTINUE, ExecutionRoute.RESUMED)),
    ;

    val ports: List<Port> get() = routes.map { execOut(it.portName, it.label) }
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
 * Escape hatch for a value whose output *schema* is chosen in its own config and so
 * cannot be a static type parameter — `value.variable`, which carries whatever type
 * the variable it names was declared as. It emits an [Item] directly, the same trade
 * [RawTransform] makes, for the same reason.
 *
 * Overriding [readRaw] rather than [read] is what keeps the promise the [ValueNode]
 * KDoc makes: both placements — the wired pull and the edge-free `val:` read — come
 * through one method, so a value cannot mean one thing wired and another named.
 */
interface RawValue<C : Any> : ValueNode<C, Unit> {
    override val definition: ValueNodeDefinition<C, Unit>

    /** The value right now as a typed item, or null when it cannot be read. */
    suspend fun readItem(config: C, context: ExecutionContext): Item?

    /** Never called: [readRaw] answers directly. */
    override suspend fun read(config: C, context: ExecutionContext): Unit? = null

    override suspend fun readRaw(
        config: Map<ConfigKey, String>,
        context: ExecutionContext,
    ): Item? = readItem(definition.schema.decode(config), context)
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

/**
 * An action whose `body` the executor pulses once per iteration, then `completed`.
 *
 * This is the Unreal Blueprints shape, and it is the reason iteration costs the
 * rest of the engine nothing: both ports point *forward*, so nothing is wired back
 * into the loop, the graph stays acyclic, and neither
 * [com.example.ottomatic.engine.validation.GraphValidator]'s cycle rule nor the
 * executor's path-scoped `onPath` guard needs an exception carved into it. (n8n's
 * *Loop Over Items* asks the user to wire the last body node back into the loop;
 * here that is an execution cycle, which both of those correctly refuse.)
 *
 * A loop **declares** the iterations rather than pulsing anything itself: it
 * returns one data map per pass — what its own DATA output ports carry that time
 * round — and the executor writes each into the run's data cache before pulsing.
 * Driving the walk from inside a node would mean duplicating the quarantine rules,
 * the log attribution and the halt propagation that already live in one place.
 *
 * [ExecOutputs.LOOP] is not optional: `body` and `completed` are the only ports
 * the executor pulses for one of these.
 */
interface LoopAction<C : Any> : ExecutableAction {
    override val definition: ActionNodeDefinition<C, Unit>

    /**
     * One entry per iteration, each holding the items this node's own DATA outputs
     * carry that pass. An empty list means the body never runs — `completed` still
     * fires, because "there was nothing to do" is not a failure.
     *
     * Must not exceed [MAX_ITERATIONS] entries; the executor truncates as a
     * backstop, but a loop that knows its own count should clamp it rather than
     * building a list it cannot use.
     */
    suspend fun iterations(
        config: C,
        input: NodeInput,
        context: ExecutionContext,
    ): List<Map<PortName, Item>>

    /** Decoding entry point, mirroring [RawAction.run]. */
    suspend fun iterationsRaw(
        node: WorkflowNode,
        data: Map<PortName, Item>,
        context: ExecutionContext,
    ): List<Map<PortName, Item>> =
        iterations(definition.schema.decode(node, data), NodeInput(node, data), context)

    /**
     * Never reached: [com.example.ottomatic.engine.WorkflowExecutor] tests for
     * `is LoopAction` before it calls this. It skips the body rather than throwing
     * so that a caller which does not know about loops degrades to "ran zero
     * times" instead of failing the run.
     */
    override suspend fun run(
        node: WorkflowNode,
        data: Map<PortName, Item>,
        context: ExecutionContext,
    ): EncodedNodeOutput = EncodedNodeOutput(
        execOut = listOf(ExecPorts.COMPLETED),
        dataOut = emptyMap(),
        halt = false,
    )
}

/**
 * A loop that decides **before each pass** whether to run another, rather than
 * settling all of them up front.
 *
 * The split from [LoopAction] is the meaningful part, not an implementation detail.
 * A `for each` *snapshots* its list when it starts — appending to that list from
 * inside the body must not extend the walk, exactly as in Unreal Blueprints — so its
 * passes are known before the first one runs. A `while` is the opposite by
 * definition: its condition is its exit, so it has to be re-evaluated against freshly
 * pulled inputs every time round, or the body could never end it.
 *
 * That re-pull is what makes the loop terminate at all: `collectDataIn` builds a
 * fresh memo per call, and an edge-free `val:` source is re-read through
 * `readRaw`, so a variable written in the body is visible to the next check.
 */
interface ConditionalLoopAction<C : Any> : ExecutableAction {
    override val definition: ActionNodeDefinition<C, Unit>

    /**
     * The DATA this node's own outputs carry for pass number [pass] (counting from
     * 0), or null to stop and pulse `completed`.
     *
     * Returning a map rather than a plain `Boolean` keeps the currency the same as
     * [LoopAction.iterations] — one map per pass — so the executor handles both
     * kinds of loop with one piece of machinery.
     */
    suspend fun nextPass(
        config: C,
        input: NodeInput,
        context: ExecutionContext,
        pass: Int,
    ): Map<PortName, Item>?

    /** Decoding entry point, mirroring [RawAction.run]. */
    suspend fun nextPassRaw(
        node: WorkflowNode,
        data: Map<PortName, Item>,
        context: ExecutionContext,
        pass: Int,
    ): Map<PortName, Item>? =
        nextPass(definition.schema.decode(node, data), NodeInput(node, data), context, pass)

    /** Never reached; see [LoopAction.run]. */
    override suspend fun run(
        node: WorkflowNode,
        data: Map<PortName, Item>,
        context: ExecutionContext,
    ): EncodedNodeOutput = EncodedNodeOutput(
        execOut = listOf(ExecPorts.COMPLETED),
        dataOut = emptyMap(),
        halt = false,
    )
}

/**
 * An action that pulses **`out` at once and `resumed` later**, with the two
 * branches walked independently.
 *
 * The third shape the executor drives itself, after [LoopAction] and
 * [ConditionalLoopAction], and for the same reason: a route is a choice between
 * outputs, and this is not a choice — both fire. Expressing it as a
 * [NodeOutput.route] could only ever pick one.
 *
 * The node **declares** the wait rather than performing the walk, exactly as a
 * loop declares its iterations: [begin] returns what each branch carries plus a
 * suspending [Fork.resume], and
 * [com.example.ottomatic.engine.WorkflowExecutor.runFork] does the rest.
 * Driving the walk from inside a node would mean duplicating the quarantine
 * rules, the log attribution and the halt propagation that already live in one
 * place.
 *
 * What a fork costs, stated once so it is not discovered in the field:
 *
 *  - the deferred branch runs on the **arm's** job, so it survives the run that
 *    started it (and `trigger.macro_finished` fires before it) but dies when the
 *    macro is disabled;
 *  - it walks a **snapshot** of the data taken at the fork, so a wire from the
 *    `out` branch into the `resumed` branch would read nothing — which
 *    `GraphValidator` refuses rather than letting it fall back silently;
 *  - `action.stop` downstream of `out` cancels a wait that has not fired yet,
 *    but cannot un-run a `resumed` branch that has already started.
 *
 * [ExecOutputs.FORK] is not optional: `out` and `resumed` are the only ports the
 * executor pulses for one of these.
 */
interface ForkAction<C : Any> : ExecutableAction {
    override val definition: ActionNodeDefinition<C, Unit>

    /**
     * What this node produces, and when — decided before either branch is pulsed.
     *
     * Returning a plan rather than blocking is what keeps the timing in the
     * executor: this call must not itself wait.
     */
    suspend fun begin(config: C, input: NodeInput, context: ExecutionContext): Fork

    /** Decoding entry point, mirroring [RawAction.run]. */
    suspend fun beginRaw(
        node: WorkflowNode,
        data: Map<PortName, Item>,
        context: ExecutionContext,
    ): Fork = begin(definition.schema.decode(node, data), NodeInput(node, data), context)

    /**
     * Never reached: [com.example.ottomatic.engine.WorkflowExecutor] tests for
     * `is ForkAction` before it calls this. It pulses the immediate branch rather
     * than throwing, so a caller which does not know about forks degrades to
     * "never resumed" instead of failing the run — the same trade [LoopAction]
     * makes with "ran zero times".
     */
    override suspend fun run(
        node: WorkflowNode,
        data: Map<PortName, Item>,
        context: ExecutionContext,
    ): EncodedNodeOutput = EncodedNodeOutput(
        execOut = listOf(ExecPorts.OUT),
        dataOut = emptyMap(),
        halt = false,
    )
}

/**
 * A [ForkAction]'s plan: what each of its two branches carries, and how to wait
 * for the second one.
 *
 * @param values the items this node's DATA outputs carry on the **immediate**
 *   branch, cached before it is walked.
 * @param resume suspends until the deferred branch should pulse, then returns
 *   what that branch's DATA outputs carry. **Null means it never pulses** — the
 *   moment is unreachable, which the node reports itself. Resuming instantly
 *   instead would be indistinguishable from a bug.
 *
 * [resume] returns a map rather than the plan carrying one up front because the
 * interesting fact about the deferred branch is *when it actually woke*, and
 * that is not known at fork time: under an inexact alarm it differs from the
 * moment that was asked for, and that difference is the whole reason the port is
 * worth having.
 */
class Fork(
    val values: Map<PortName, Item> = emptyMap(),
    val resume: (suspend () -> Map<PortName, Item>)?,
)

/**
 * The most deferred branches that may be waiting across the whole process.
 *
 * A `Repeat` of a thousand around a `Wait Until` would otherwise arm a thousand
 * platform alarms — well past the point AlarmManager starts throttling an app —
 * and hold a thousand graph snapshots against them. Over the cap the immediate
 * branch still runs and the deferred one is skipped **with a line in the run
 * log**, never silently: a fork that quietly stopped forking would read exactly
 * like a moment that had not arrived yet.
 */
const val MAX_PENDING_WAITS: Int = 64

/**
 * The deferred fork branches currently waiting, counted across every workflow.
 *
 * Process-wide because what it bounds is process-wide — the platform alarms
 * behind those branches, which are throttled per app and which no single macro's
 * count would limit.
 *
 * [count] is readable from outside the executor for one specific reason: a
 * foreground service that stops itself the moment nothing is armed would take a
 * pending wait down with it, and a macro run from a home-screen tile has no arm
 * to keep it up. Something waiting is something still to do.
 */
object PendingWaits {

    private val waiting = AtomicInteger()

    /** How many deferred branches are waiting right now. */
    val count: Int get() = waiting.get()

    /** Takes a slot, or answers false when [MAX_PENDING_WAITS] are already taken. */
    internal fun reserve(): Boolean {
        if (waiting.incrementAndGet() <= MAX_PENDING_WAITS) return true
        waiting.decrementAndGet()
        return false
    }

    /** Gives back a slot taken by [reserve]. */
    internal fun release() {
        waiting.decrementAndGet()
    }
}

/**
 * The most iterations one loop may run.
 *
 * A `Repeat` configured with a million — by a typo, or by a number that arrived
 * down a wire — would otherwise hold a million maps in memory and spin a
 * foreground service until Android's watchdog killed the app. Truncation is
 * always announced in the run log, never silent: a capped loop that said nothing
 * would read exactly like one that finished.
 *
 * It matters most for [ConditionalLoopAction], which is the only node whose pass
 * count nobody states: a condition the body never changes would otherwise loop for
 * as long as the process lives.
 */
const val MAX_ITERATIONS: Int = 1_000
