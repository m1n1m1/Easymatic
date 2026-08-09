package com.example.ottomatic.engine

import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.LogSource
import com.example.ottomatic.domain.model.DataConnection
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.schema.asText
import com.example.ottomatic.domain.registry.ActionRegistry
import com.example.ottomatic.domain.registry.TransformRegistry
import com.example.ottomatic.domain.registry.ValueRegistry
import com.example.ottomatic.engine.trigger.TriggerOutput
import com.example.ottomatic.engine.validation.GraphValidation
import com.example.ottomatic.engine.validation.GraphValidator
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Walks the workflow graph from a fired trigger and runs connected actions.
 *
 * Execution model:
 *  1. A trigger fires a [TriggerOutput]. Its [TriggerOutput.value] items are
 *     cached in the [DataCache] keyed by node id and port name.
 *  2. The executor follows the trigger's EXECUTION `out` port, running each
 *     connected action once. Encoded outputs are cached and followed.
 *  3. Failed actions log and stop their branch.
 *
 * **A problem stops the smallest thing it can.** [GraphValidator] runs once per
 * run and names, per finding, the edges and nodes that must not run; everything
 * else does. So a loop wired into one trigger's branch costs that one wire, and
 * every sibling branch — and every other trigger in the same workflow — still
 * runs. The alternative, refusing the whole graph, made one bad edge look
 * identical to a macro that had never been armed.
 *
 * Data semantics: a data edge whose source is an *action or trigger* must be
 * exec-upstream of its target (enforced by [GraphValidator]) so the source has run
 * by the time the target executes. [collectDataIn] follows each
 * [com.example.ottomatic.domain.model.DataConnection] into the target and reads
 * the source port's cached item. An unwired data input port simply yields no
 * entry, and the action's config class falls back to that property's form value.
 *
 * A data edge whose source is a [com.example.ottomatic.engine.ValueNode] or an
 * [ExecutableTransform] works the other way round — pull, not push. Neither is ever
 * pulsed and neither has an exec position at all; both are *read* while collecting
 * their consumer's inputs. The rule is one sentence: **a value is read just before
 * the node that uses it.** Within one consumer every port sees a single read (so two
 * ports of the same node can never disagree), while two different consumers each read
 * fresh (so a value can never go stale across a delay, or across a future loop body).
 *
 * A transform extends that rule rather than bending it: pulling one first pulls
 * whatever feeds it, so a whole chain of conversions resolves in one go, sharing a
 * single memo — a value node reaching one consumer through two different transforms
 * is still read once.
 *
 * Iteration ([LoopAction], [runLoop]) is the one place the executor pulses the same
 * port more than once. It stays a *forward* walk: a loop declares its iterations and
 * this class pulses `body` for each, then `completed`. Nothing is wired back into the
 * loop, so the graph is still acyclic and both the validator's cycle rule and the
 * `onPath` guard below apply unchanged.
 *
 * A fork ([ForkAction], [runFork]) is the one place the walk **splits**: `out` is
 * pulsed now and `resumed` when the node's wait is over, and the second branch is
 * detached onto a scope that outlives the run. From there on the two are
 * genuinely separate walks over a shared graph, each with its own copy of the run
 * state — see [Run.fork] for why copying rather than sharing is required, and
 * [ForkAction] for what that costs.
 */
/** Items produced so far, addressed by the port they were produced on. */
private typealias DataCache = MutableMap<Pair<NodeId, PortName>, Item>

// One walk, split into the steps it genuinely has: follow an edge, run a node, run
// a loop, collect inputs, pull a transform, pull a value, log. Merging any two to
// come under the threshold would hide a distinction the KDoc above spends its length
// explaining.
@Suppress("TooManyFunctions")
class WorkflowExecutor(
    private val context: ExecutionContext,
    /**
     * Where a [ForkAction]'s deferred branch runs, or null to run it **inline**.
     *
     * It cannot run under the caller's own coroutine: that would keep
     * [runFromTrigger] suspended for the whole wait, which stops the trigger
     * collecting further events, delays `trigger.macro_finished` by hours and
     * leaves a widget tile on "running" — i.e. exactly the blocking a fork exists
     * to remove. So it is launched on a scope that outlives the run, and the one
     * with the right lifetime is the **arm**: cancelled when the macro is
     * disabled or re-armed, and by nothing else.
     *
     * Null runs the deferred branch inline instead, which is what an engine-only
     * test wants — no scope to supply, and the assertions stay deterministic.
     */
    private val deferredScope: CoroutineScope? = null,
) {

    /**
     * Everything one run carries with it.
     *
     * Threaded rather than held as a field, for the same reason [runId] is minted
     * per call: with `action.delay` in the palette and one coroutine per trigger
     * flow, two runs of the same workflow genuinely overlap. A field would have them
     * share a data cache and a cycle guard, and the interleaving would be invisible.
     */
    private class Run(
        val workflow: Workflow,
        val runId: Long,
        val validation: GraphValidation,
        val dataCache: DataCache = mutableMapOf(),
        /**
         * The nodes on the exec path currently being walked — **not** the nodes that
         * have run. See [pulse].
         */
        val onPath: MutableSet<NodeId> = HashSet(),
    ) {
        /**
         * A copy of this run for a [ForkAction]'s deferred branch, taken at the
         * moment of the fork.
         *
         * Copied rather than shared for two independent reasons, either of which
         * would be enough:
         *
         *  - **[onPath]**. [pulse]'s `finally` removes the fork node once
         *    [runFork] returns, while the deferred branch is still to come. A
         *    shared set would leave that branch unguarded, so a `resumed` wire
         *    reaching back would recurse until the stack gave out — the exact
         *    failure the guard exists to prevent. A copy holds the ancestor
         *    chain, and re-entry hits the ordinary "Execution cycle" error.
         *  - **[dataCache]**. The deferred branch runs minutes or hours later.
         *    "It sees the graph as it was at the fork" is the only rule that can
         *    be explained; "it sees whatever the other branch had got round to
         *    writing" is not a rule at all. [Item] is immutable, so a shallow
         *    copy is enough — and it is also what makes the two branches safe to
         *    walk in parallel, which on the service's dispatcher they genuinely
         *    are.
         */
        fun fork(): Run = Run(workflow, runId, validation, HashMap(dataCache), HashSet(onPath))
    }

    /**
     * Runs the graph once, under a fresh run id.
     *
     * A run *is* one call of this method, which is why the id is minted here and
     * threaded down rather than held on the executor.
     *
     * Problems are announced as a **single summary line**, not one line per finding.
     * A macro firing every minute against a graph with one bad edge would otherwise
     * write that inventory into the console every minute and evict the trace that
     * explains what actually happened — and since the run now continues, that trace
     * is the interesting part. The full list belongs in the editor's Problems panel,
     * where it does not repeat; what lands here is only what a given run tripped over.
     */
    suspend fun executeFrom(workflow: Workflow, triggerNode: WorkflowNode, output: TriggerOutput) {
        val runId = runIds.incrementAndGet()
        val at = context.scoped(source(workflow, runId, triggerNode))
        at.log("Triggered by '${triggerNode.name}'")
        val validation = GraphValidator(workflow).validate()
        if (validation.errors.isNotEmpty()) {
            at.log("${validation.errors.size} problem(s) in this workflow; affected steps are skipped", LogLevel.ERROR)
        }
        if (triggerNode.id in validation.blockedNodes) {
            at.log("'${triggerNode.name}' cannot run until its problem is fixed", LogLevel.ERROR)
            return
        }
        // What the trigger actually delivered. Everything downstream is derived
        // from it, so a run that surprises you is very often wrong right here.
        logData(at, OUT_LABEL, output.value)
        val run = Run(workflow, runId, validation)
        output.value.forEach { (port, item) ->
            run.dataCache[triggerNode.id to port] = item
        }
        run.onPath += triggerNode.id
        pulse(run, triggerNode, ExecutionRoute.OUT.portName)
        at.log("Run finished", LogLevel.DEBUG)
    }

    /**
     * Follows one exec output port, running each node it reaches. Returns false
     * when something halted the chain, which unwinds this branch of the walk.
     *
     * The return value is what makes `action.stop` mean the same thing inside a
     * loop body as anywhere else. [runLoop] pulses `body` once per iteration, so a
     * halt that only unwound the current pulse would quietly start the next one —
     * the macro would keep running after the step that stopped it.
     *
     * [Run.onPath] is a **path-scoped** guard, added before a node runs and removed
     * in a `finally` on the way back out — the same shape as the `visiting` set in
     * [readTransform]. It must not be a global visited set: a diamond (T→A, T→B,
     * A→J, B→J) is supposed to run J once per incoming pulse, and a visited set
     * would silently swallow the second. It is also what lets a loop body re-run
     * every iteration: the body's nodes are added and removed inside each pulse,
     * while the loop node itself stays on the path throughout. What it stops is a
     * true loop, in a graph that reached the engine hand-edited or armed before the
     * cycle rule existed — where the previous version recursed until the stack gave
     * out.
     *
     * The return value describes **this** walk. A [ForkAction]'s deferred branch is
     * a second one, and whether it halted is not reported here — by the time it
     * runs, the caller that would have unwound is long gone. [runFork] answers for
     * the immediate branch only.
     */
    @Suppress("LoopWithTooManyJumpStatements")
    private suspend fun pulse(run: Run, node: WorkflowNode, port: PortName): Boolean {
        val outgoing = run.workflow.outgoingExec(node.id, port)
        for (connection in outgoing) {
            val target = run.workflow.node(connection.toNodeId) ?: continue
            // Scoping here is what gives every `context.log(…)` written inside an
            // action body its node, without a single action knowing about it —
            // an action never receives its own WorkflowNode, only this does.
            val at = context.scoped(source(run.workflow, run.runId, target))
            val blocked = blockedReason(run, connection, node, target)
            if (blocked != null) {
                at.log(blocked, LogLevel.ERROR)
                continue
            }
            val action = ActionRegistry.byId(target.typeId) ?: continue
            if (!run.onPath.add(target.id)) {
                at.log("Execution cycle: '${target.name}' is already running on this path", LogLevel.ERROR)
                continue
            }
            try {
                if (!runNode(run, action, target, at)) return false
            } finally {
                run.onPath.remove(target.id)
            }
        }
        return true
    }

    /**
     * Why [target] must not be reached over [connection], or null when it may be.
     *
     * Attributed to [target] rather than to [from]: the interesting fact is which
     * step did not happen, and that is the card the console's tap-to-select should
     * land on.
     */
    private fun blockedReason(
        run: Run,
        connection: com.example.ottomatic.domain.model.ExecConnection,
        from: WorkflowNode,
        target: WorkflowNode,
    ): String? = when {
        connection.id in run.validation.blockedConnections ->
            "Not reached from '${from.name}': that wire has a problem"
        target.id in run.validation.blockedNodes ->
            "Skipped: this step has a problem that must be fixed first"
        else -> null
    }

    /**
     * Runs one node and follows its exec outputs. Returns false when the chain
     * halted, which unwinds this branch of the walk.
     */
    @Suppress("ReturnCount") // "kept walking" has three distinct ways of being decided.
    private suspend fun runNode(
        run: Run,
        action: ExecutableAction,
        target: WorkflowNode,
        at: ExecutionContext,
    ): Boolean {
        if (action is LoopAction<*>) return runLoop(run, action, target, at)
        if (action is ConditionalLoopAction<*>) return runConditionalLoop(run, action, target, at)
        if (action is ForkAction<*>) return runFork(run, action, target, at)
        val dataIn = collectDataIn(run, target)
        at.log("→ ${target.name}", LogLevel.DEBUG)
        logData(at, IN_LABEL, dataIn)
        val result = runCatching { action.run(target, dataIn, at) }.getOrElse { e ->
            // A cancelled run is not a failed action: swallowing this logged a
            // bogus failure and then carried on walking a graph nobody was waiting for.
            if (e is CancellationException) throw e
            at.log("Action ${target.typeId} failed: ${e.message}", LogLevel.ERROR)
            null
        } ?: return true
        logData(at, OUT_LABEL, result.dataOut)
        result.dataOut.forEach { (producedOn, item) ->
            run.dataCache[target.id to producedOn] = item
        }
        if (result.halt) {
            at.log("Action ${target.typeId} halted execution chain")
            return false
        }
        for (execPort in result.execOut) {
            if (!pulse(run, target, execPort)) return false
        }
        return true
    }

    /**
     * Runs a [LoopAction]: `body` once per iteration, then `completed` once.
     *
     * The loop node's own DATA outputs are written into the run's cache *before*
     * each body pulse and overwritten on the next, which is exactly right because a
     * body node reads them while that iteration is running. Nothing needs to
     * snapshot or restore them: [collectDataIn] builds a fresh memo per consuming
     * node, so a value node or transform inside the body is re-read every pass
     * rather than frozen at the value it had on the first one.
     *
     * The one thing that *is* snapshotted is a [ForkAction] in the body, and this
     * is why it has to be: iteration 3's deferred branch fires after the loop has
     * moved on, and only a copy taken at its own fork still carries iteration 3's
     * item and index.
     *
     * `completed` fires even when the body never ran. An empty list is not a
     * failure — "there was nothing to send" is a perfectly good outcome, and a
     * macro that silently stopped there would be indistinguishable from one whose
     * loop was wired wrong.
     *
     * The iteration count is capped as a backstop even though both loop nodes clamp
     * their own: the cap has to hold for whatever loop is written next, and a
     * truncation nobody announced reads like a run that covered everything.
     */
    @Suppress("ReturnCount")
    private suspend fun runLoop(
        run: Run,
        loop: LoopAction<*>,
        target: WorkflowNode,
        at: ExecutionContext,
    ): Boolean {
        val dataIn = collectDataIn(run, target)
        at.log("→ ${target.name}", LogLevel.DEBUG)
        logData(at, IN_LABEL, dataIn)
        val declared = runCatching { loop.iterationsRaw(target, dataIn, at) }.getOrElse { e ->
            if (e is CancellationException) throw e
            at.log("Action ${target.typeId} failed: ${e.message}", LogLevel.ERROR)
            null
        } ?: return true
        val iterations = declared.take(MAX_ITERATIONS)
        if (declared.size > iterations.size) {
            at.log(
                "'${target.name}' stopped after $MAX_ITERATIONS of ${declared.size} repeats",
                LogLevel.WARN,
            )
        }
        at.log("'${target.name}' repeating ${iterations.size} time(s)", LogLevel.DEBUG)
        for (values in iterations) {
            currentCoroutineContext().ensureActive()
            values.forEach { (producedOn, item) -> run.dataCache[target.id to producedOn] = item }
            logData(at, OUT_LABEL, values)
            if (!pulse(run, target, ExecutionRoute.BODY.portName)) return false
        }
        return pulse(run, target, ExecutionRoute.COMPLETED.portName)
    }

    /**
     * Runs a [ForkAction]: pulse `out` now, and `resumed` when its wait is over.
     *
     * The deferred branch is **detached** onto [deferredScope] rather than awaited
     * here. Awaiting it would suspend [runFromTrigger] for the length of the wait,
     * and three things downstream read that as the run still being in progress —
     * the trigger's `flow.collect`, which would swallow every event meanwhile;
     * `trigger.macro_finished`, emitted from that function's `finally`; and a
     * widget tile, which would sit on "running" all night. With no scope supplied
     * it runs inline instead ([forkInline]), which is what a test wants.
     *
     * The branch walks [Run.fork]'s snapshot, taken here and synchronously — doing
     * it inside the launched block would race the immediate branch, which by then
     * is already writing into the original.
     *
     * The launched block carries its own `catch` because it has escaped
     * [runFromTrigger]'s: an exception thrown hours later has no caller left to
     * report it, and on the service's scope it would reach the process handler.
     */
    @Suppress("ReturnCount") // Skipped-because-capped, never-resumes and halted are three different endings.
    private suspend fun runFork(
        run: Run,
        fork: ForkAction<*>,
        target: WorkflowNode,
        at: ExecutionContext,
    ): Boolean {
        val dataIn = collectDataIn(run, target)
        at.log("→ ${target.name}", LogLevel.DEBUG)
        logData(at, IN_LABEL, dataIn)
        val begun = runCatching { fork.beginRaw(target, dataIn, at) }.getOrElse { cause ->
            if (cause is CancellationException) throw cause
            at.log("Action ${target.typeId} failed: ${cause.message}", LogLevel.ERROR)
            null
        } ?: return true
        begun.values.forEach { (producedOn, item) -> run.dataCache[target.id to producedOn] = item }
        logData(at, OUT_LABEL, begun.values)
        val snapshot = run.fork()
        val resume = begun.resume
        if (resume == null) {
            at.log("'${target.name}' has nothing left to wait for, so it will not resume", LogLevel.WARN)
            return pulse(run, target, ExecutionRoute.CONTINUE.portName)
        }
        // The cap is announced rather than silent, for the reason MAX_ITERATIONS is:
        // a fork that quietly stopped forking reads exactly like a moment that has
        // not come yet. The immediate branch still runs.
        if (!PendingWaits.reserve()) {
            at.log(
                "'${target.name}' is not waiting: $MAX_PENDING_WAITS waits are already pending",
                LogLevel.WARN,
            )
            return pulse(run, target, ExecutionRoute.CONTINUE.portName)
        }
        val scope = deferredScope
            ?: return forkInline(run, snapshot, target, at, resume)
        val deferred = scope.launch { awaitAndPulse(snapshot, target, at, resume) }
        if (!pulse(run, target, ExecutionRoute.CONTINUE.portName)) {
            // `action.stop` on the immediate branch, while the moment is still
            // ahead. Stopping a macro that is visibly still waiting has to stop
            // the wait too, or "Stop Macro" would not stop the macro.
            deferred.cancel()
            return false
        }
        return true
    }

    /**
     * A fork with nowhere to detach to: walk the immediate branch, then wait here.
     *
     * The order matters even though there is no concurrency — `out` still means
     * "now" and `resumed` still means "later", so an assertion written against one
     * mode holds in the other. A halted immediate branch skips the wait outright,
     * which is what cancelling the job does in the detached case.
     */
    private suspend fun forkInline(
        run: Run,
        snapshot: Run,
        target: WorkflowNode,
        at: ExecutionContext,
        resume: suspend () -> Map<PortName, Item>,
    ): Boolean {
        val kept = pulse(run, target, ExecutionRoute.CONTINUE.portName)
        if (kept) {
            awaitAndPulse(snapshot, target, at, resume)
        } else {
            // Nothing will run the `finally` that normally gives the slot back.
            PendingWaits.release()
        }
        return kept
    }

    /** One deferred branch: wait, cache what it produced, walk it. */
    @Suppress("TooGenericExceptionCaught") // Hours later there is no caller left to report to.
    private suspend fun awaitAndPulse(
        snapshot: Run,
        target: WorkflowNode,
        at: ExecutionContext,
        resume: suspend () -> Map<PortName, Item>,
    ) {
        try {
            val values = resume()
            values.forEach { (producedOn, item) -> snapshot.dataCache[target.id to producedOn] = item }
            logData(at, OUT_LABEL, values)
            pulse(snapshot, target, ExecutionRoute.RESUMED.portName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            at.log("Action ${target.typeId} failed while waiting: ${e.message}", LogLevel.ERROR)
        } finally {
            PendingWaits.release()
        }
    }

    /**
     * Runs a [ConditionalLoopAction]: ask, pulse `body`, ask again, and pulse
     * `completed` once the answer is no.
     *
     * **The inputs are re-collected every pass, and that is the whole design.** A
     * `for each` resolves its list once because appending to it from inside the body
     * must not extend the walk; a `while` is the exact opposite — its condition is
     * its only exit, so it has to see what the body just did or it could never stop.
     * [collectDataIn] builds a fresh memo per call, so a value node, a transform
     * chain and an edge-free `val:` read all answer anew each time round.
     *
     * The cap is checked *before* the body rather than after the condition, so a
     * runaway loop runs exactly [MAX_ITERATIONS] passes and says so. This is the one
     * node whose pass count nobody states, so it is also the one where a silent cap
     * would be indistinguishable from a condition that finally went false.
     */
    @Suppress("ReturnCount") // Halted, failed and finished are three genuinely different endings.
    private suspend fun runConditionalLoop(
        run: Run,
        loop: ConditionalLoopAction<*>,
        target: WorkflowNode,
        at: ExecutionContext,
    ): Boolean {
        at.log("→ ${target.name}", LogLevel.DEBUG)
        var passes = 0
        var asking = true
        var failed = false
        // The cap lives in the loop condition rather than in a `break`, so "it ran out
        // of passes" and "its condition went false" stay distinguishable afterwards:
        // still asking when the loop exits means the cap is what stopped it.
        while (asking && passes < MAX_ITERATIONS) {
            currentCoroutineContext().ensureActive()
            val dataIn = collectDataIn(run, target)
            if (passes == 0) logData(at, IN_LABEL, dataIn)
            val values = runCatching { loop.nextPassRaw(target, dataIn, at, passes) }.getOrElse { cause ->
                if (cause is CancellationException) throw cause
                at.log("Action ${target.typeId} failed: ${cause.message}", LogLevel.ERROR)
                failed = true
                null
            }
            if (values == null) {
                asking = false
            } else {
                passes++
                values.forEach { (producedOn, item) -> run.dataCache[target.id to producedOn] = item }
                logData(at, OUT_LABEL, values)
                if (!pulse(run, target, ExecutionRoute.BODY.portName)) return false
            }
        }
        // A failed condition pulses nothing, exactly as a failed action does.
        if (failed) return true
        if (asking) {
            at.log(
                "'${target.name}' stopped after $MAX_ITERATIONS repeats; its condition never went false",
                LogLevel.WARN,
            )
        }
        at.log("'${target.name}' repeated $passes time(s)", LogLevel.DEBUG)
        return pulse(run, target, ExecutionRoute.COMPLETED.portName)
    }

    /**
     * Collects the typed [Item]s arriving on [target]'s DATA input ports by
     * following each [com.example.ottomatic.domain.model.DataConnection] into
     * [target]. An edge from an action or trigger reads that source port's cached
     * item; an edge from a value node *reads the value now*. Missing sources are
     * skipped (they produce no entry), and the action's config class then falls
     * back to the form value of the corresponding property.
     *
     * [reads] is deliberately a local: it memoizes each value node for the duration
     * of this one call, which is exactly the "fresh per consumer" rule — consistent
     * across [target]'s own ports, re-read for the next consumer.
     */
    private suspend fun collectDataIn(
        run: Run,
        target: WorkflowNode,
        reads: MutableMap<NodeId, Item?> = HashMap(),
        visiting: MutableSet<NodeId> = HashSet(),
    ): Map<PortName, Item> {
        val incoming = run.workflow.incomingData(target.id)
        if (incoming.isEmpty()) return emptyMap()
        val result = HashMap<PortName, Item>(incoming.size)
        for (conn in incoming) {
            val item = resolveDataIn(run, conn, target, reads, visiting)
            if (item != null) result[conn.toPort] = item
        }
        return result
    }

    /**
     * The item arriving over [conn]: a cached output for a pushed source, or a live
     * pull for a value node or a transform — memoized in [reads] so [target] sees
     * one consistent value however many of its ports the same source feeds, whether
     * directly or through different transforms.
     *
     * A quarantined edge carries nothing. In the ordinary case the node reading it
     * has already been held back by [pulse], so this never comes up; it matters for
     * the one case where the validator could not name a consumer — a transform chain
     * ending nowhere — and it costs one set lookup to be sure.
     */
    @Suppress("ReturnCount", "LongParameterList") // Null-guards on the optional node/cache path are idiomatic here.
    private suspend fun resolveDataIn(
        run: Run,
        conn: DataConnection,
        target: WorkflowNode,
        reads: MutableMap<NodeId, Item?>,
        visiting: MutableSet<NodeId>,
    ): Item? {
        if (conn.id in run.validation.blockedConnections) return null
        val sourceNode = run.workflow.node(conn.fromNodeId) ?: return null
        if (conn.fromNodeId in reads) return reads[conn.fromNodeId]
        ValueRegistry.byId(sourceNode.typeId)?.let { value ->
            return readValue(run, value, sourceNode, target).also { reads[conn.fromNodeId] = it }
        }
        TransformRegistry.byId(sourceNode.typeId)?.let { transform ->
            return readTransform(run, transform, sourceNode, target, reads, visiting)
                .also { reads[conn.fromNodeId] = it }
        }
        return run.dataCache[conn.fromNodeId to conn.fromPort]
    }

    /**
     * Pulls [transform] for [target], first pulling whatever *it* depends on.
     *
     * The recursion shares [reads], which is what keeps the "one consistent read per
     * consumer" rule honest across a chain: a value node feeding two transforms that
     * both feed [target] is still read exactly once.
     *
     * [visiting] guards against a data cycle. [GraphValidator] reports those and
     * quarantines the edge that closes them, so this only stops a hand-edited
     * workflow file from recursing until the stack gives out.
     */
    @Suppress("LongParameterList") // The pull memo and cycle guard travel with the recursion.
    private suspend fun readTransform(
        run: Run,
        transform: ExecutableTransform,
        sourceNode: WorkflowNode,
        target: WorkflowNode,
        reads: MutableMap<NodeId, Item?>,
        visiting: MutableSet<NodeId>,
    ): Item? {
        // Attributed to the transform, not to whoever pulled it: the line is
        // about that node, and it is the one the console should select.
        val at = context.scoped(source(run.workflow, run.runId, sourceNode))
        if (!visiting.add(sourceNode.id)) {
            at.log("Transform ${transform.typeId.value} skipped: it depends on itself", LogLevel.ERROR)
            return null
        }
        return try {
            val data = collectDataIn(run, sourceNode, reads, visiting)
            logData(at, IN_LABEL, data)
            val item = runCatching { transform.transformRaw(sourceNode, data, at) }.getOrElse { cause ->
                if (cause is CancellationException) throw cause
                at.log("Transform ${transform.typeId.value} failed: ${cause.message}", LogLevel.ERROR)
                null
            }
            if (item == null) {
                at.log("Transform ${transform.typeId.value} produced nothing for '${target.name}'", LogLevel.WARN)
            } else {
                at.log("Transform ${transform.typeId.value} = ${item.value} for '${target.name}'", LogLevel.DEBUG)
            }
            item
        } finally {
            visiting.remove(sourceNode.id)
        }
    }

    /**
     * Reads [value] for [target], logging the outcome.
     *
     * A pulled value has no node-by-node line in the run log of its own, so without
     * this a failing comparison or a surprising notification would be undiagnosable.
     */
    private suspend fun readValue(
        run: Run,
        value: ValueNode<*, *>,
        sourceNode: WorkflowNode,
        target: WorkflowNode,
    ): Item? {
        val at = context.scoped(source(run.workflow, run.runId, sourceNode))
        val item = runCatching { value.readRaw(sourceNode.config, at) }.getOrElse { cause ->
            if (cause is CancellationException) throw cause
            at.log("Read ${value.typeId.value} failed: ${cause.message}", LogLevel.ERROR)
            null
        }
        if (item == null) {
            at.log("Read ${value.typeId.value} unavailable for '${target.name}'", LogLevel.WARN)
        } else {
            at.log("Read ${value.typeId.value} = ${item.value} for '${target.name}'", LogLevel.DEBUG)
        }
        return item
    }

    private fun source(workflow: Workflow, runId: Long, node: WorkflowNode) =
        LogSource(workflow.id, runId, node.id.value, node.name)

    /**
     * The data crossing a node, as one line per direction.
     *
     * Knowing *that* a node ran only answers half the question; the other half is
     * always "with what?". This is where a wire that silently carried nothing, a
     * number that arrived as text, or a JSON path that matched the wrong field
     * becomes visible — none of which is deducible from the outcome alone.
     *
     * Only what came down a *wire* appears. A field typed into the form is not
     * here, because it is already on the card: the console's job is the part of a
     * node's input that is invisible until it runs.
     *
     * Silent when there is nothing, so an effect node with no data does not pay a
     * line saying so.
     */
    private fun logData(context: ExecutionContext, label: String, data: Map<PortName, Item>) {
        if (data.isEmpty()) return
        val rendered = data.entries.joinToString(separator = SEPARATOR) { (port, item) ->
            "${port.value} = ${preview(item)}"
        }
        context.log("$label $rendered", LogLevel.DEBUG)
    }

    private companion object {

        const val IN_LABEL = "in "
        const val OUT_LABEL = "out"
        const val SEPARATOR = "  ·  "

        /**
         * Enough of a value to recognise it, and never more.
         *
         * An `HttpResponseItem` body runs to megabytes. Held untruncated in a
         * 500-entry buffer per workflow, one polling macro would exhaust the heap
         * — so the cut happens here, on the way in, rather than in the console
         * that displays it.
         */
        const val MAX_VALUE_CHARS = 200

        /**
         * [Item.asText] is the renderer the rest of the app already agrees on —
         * primitives plainly, structs as compact JSON — so a value reads in the
         * console exactly as it would in a notification, and it never throws.
         */
        fun preview(item: Item): String {
            val text = item.asText()
            return when {
                text.isEmpty() -> "(empty)"
                text.length <= MAX_VALUE_CHARS -> text
                else -> text.take(MAX_VALUE_CHARS) + "… (${text.length} chars)"
            }
        }

        /**
         * Process-wide, because a run has to be distinguishable from every other
         * one showing up in the same console — and one executor is built per
         * arm, so a per-instance counter would restart on every re-arm. Counts up
         * from 1, which is what leaves [LogSource.NO_RUN] free for arm-time lines.
         */
        val runIds = AtomicLong()
    }
}
