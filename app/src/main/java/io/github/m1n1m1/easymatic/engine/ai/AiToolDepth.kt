package io.github.m1n1m1.easymatic.engine.ai

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * How deep the app currently is inside AI-driven work, process-wide.
 *
 * **Nothing else counts this, and without it a loop is reachable in one step.** A
 * macro tool runs another macro through `runFromTrigger`, which mints its own `Run`
 * with its own `dataCache` and its own `onPath` — so the executor's cycle guard, which
 * is per-run by design, sees nothing. A macro whose agent node can call that same
 * macro would recurse until the stack or the coroutine died, and the only trace would
 * be a crash with no macro named in it.
 *
 * [io.github.m1n1m1.easymatic.engine.PendingWaits]' shape and its reason: a bound that
 * belongs to the *process* rather than to any one run has to live outside every run.
 */
object AiToolDepth {

    /**
     * How many AI tool calls may be nested.
     *
     * Two, so an agent may run a macro that itself uses an agent, and no further.
     * Deeper than that is a design somebody should have written differently, and
     * every level multiplies both the bill and the time before anything visible
     * happens.
     */
    const val MAX_DEPTH = 2

    private val depth = AtomicInteger(0)

    /** The macros currently being run by a tool, so one cannot re-enter itself. */
    private val running = Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * What happened when work was attempted one level deeper.
     *
     * **Three outcomes rather than a nullable result**, and the distinction is not
     * cosmetic: "too deep" and "that one is already running" send the model to
     * different places, and a nullable return collapses them — worse, it collapses
     * them with a block that legitimately answered null.
     */
    sealed interface Attempt<out T> {

        /** Already [MAX_DEPTH] levels down. */
        data object TooDeep : Attempt<Nothing>

        /** That macro is already in flight as a tool call further up. */
        data object Busy : Attempt<Nothing>

        data class Done<out T>(val value: T) : Attempt<T>
    }

    /**
     * Runs [block] one level deeper, or reports that it would be too deep.
     *
     * A refusal is reported rather than thrown, because the caller is a tool
     * invocation and this is something the *model* should be told — it can then do
     * something else, which is the whole reason a tool result carries an error flag.
     */
    suspend fun <T> nested(block: suspend () -> T): Attempt<T> {
        if (depth.incrementAndGet() > MAX_DEPTH) {
            depth.decrementAndGet()
            return Attempt.TooDeep
        }
        return try {
            Attempt.Done(block())
        } finally {
            depth.decrementAndGet()
        }
    }

    /**
     * [nested], with [macroId] additionally marked as in-flight.
     *
     * The second guard catches what depth alone cannot: a macro that calls itself is a
     * loop at depth one, and would otherwise reach [MAX_DEPTH] before anything
     * objected — running the macro twice on the way.
     */
    suspend fun <T> nestedMacro(macroId: String, block: suspend () -> T): Attempt<T> {
        if (!running.add(macroId)) return Attempt.Busy
        return try {
            nested(block)
        } finally {
            running.remove(macroId)
        }
    }

    /** Test seam: forgets every in-flight run. */
    internal fun reset() {
        depth.set(0)
        running.clear()
    }
}
