package io.github.m1n1m1.easymatic.core.service

/**
 * Allows an action to enable, disable, list or run another macro at runtime.
 *
 * Implemented in `data/` on top of the [io.github.m1n1m1.easymatic.engine.service.MacroEngineService]
 * enable/disable intents and `WorkflowRepository.setEnabled`. Exposed to
 * actions via [io.github.m1n1m1.easymatic.engine.ExecutionContext.macroControl]
 * (nullable so engine-only tests need not supply it).
 *
 * [enable] and [disable] are fire-and-forget: the persistence and arming happen
 * asynchronously; `success` reports only that the request was dispatched. [run] is
 * not — it waits, because its caller needs an answer.
 *
 * **One facade for macros rather than a second one beside it**, on [SmartHome]'s
 * shape: that interface holds both the write (`apply`) and the read (`read`) of one
 * subject, and splitting a `MacroReader` out from a `MacroControl` would mean two
 * things on [io.github.m1n1m1.easymatic.engine.ExecutionContext] that are always wired
 * together and always by the same implementation.
 */
interface MacroControl {

    /** Persists `enabled=true` and arms [macroId]'s triggers. */
    fun enable(macroId: String): Boolean

    /** Persists `enabled=false` and disarms [macroId]'s triggers. */
    fun disable(macroId: String): Boolean

    /**
     * Every macro that can be started by name, with the inputs it declares.
     *
     * "Can be started by name" means **carrying a `trigger.api` node**, and that is
     * not an arbitrary filter: that node is already the app's answer to "run this
     * macro, with these named typed values, if it is enabled", built for callers
     * outside the process. Reusing it means a macro becomes AI-callable by having the
     * trigger its author already added, with no second opt-in to maintain and no
     * second set of rules about what a caller may start.
     */
    suspend fun callable(): List<CallableMacro> = emptyList()

    /**
     * Runs [macroId]'s `trigger.api` node with [inputs] and waits for it to finish.
     *
     * Waits — unlike [enable] and [disable] — because the caller is a tool call, and a
     * model told "started" would go on to read the results of work that has not
     * happened yet.
     */
    suspend fun run(macroId: String, inputs: Map<String, String>): MacroRunResult =
        MacroRunResult(ran = false, error = "Running another macro is not available here")
}

/**
 * A macro something outside it can start.
 *
 * [inputs] is the **raw `@Ports` spec text** rather than a parsed list, because
 * `PortSpec` lives in `domain` and `core` may not import it —
 * [MessengerRecipe]'s situation exactly, and solved the same way: the facade carries
 * the text and the consumer, which may import `domain`, parses it.
 */
data class CallableMacro(
    val id: String,
    /**
     * What the macro is called — its `trigger.api` node's caller-facing name, which
     * falls back to the macro's own.
     *
     * There is no separate description field, because a macro has no second place to
     * put one: this name *is* the sentence its author wrote for callers outside the
     * app, and it is the same sentence a model should read.
     */
    val name: String,
    val inputs: String = "",
)

/**
 * What running a macro did.
 *
 * **There is no result value, and that is a property of macros rather than an
 * omission here.** A macro has no return: `runFromTrigger` answers a `Boolean`, and
 * inventing an output would mean a node kind that can produce one, a place to declare
 * its type, and a rule for what happens when the macro takes a branch that never
 * reaches it. Anything a called macro computes comes back through a **global
 * variable**, which the caller can then read — and which an AI can read for itself
 * with the `value.variable` tool.
 */
data class MacroRunResult(
    val ran: Boolean,
    val error: String = "",
)
