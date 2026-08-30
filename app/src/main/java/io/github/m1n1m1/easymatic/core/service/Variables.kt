package io.github.m1n1m1.easymatic.core.service

/**
 * Named values a macro can write in one run and read in another.
 *
 * The graph is otherwise entirely stateless: an item exists for as long as the
 * execution that produced it, and `action.script` gets a fresh isolate every
 * time, so even code cannot remember anything. Variables are the one place
 * something persists — which is what makes "count how many times this happened",
 * "only if it is not already on" and "what was the battery last time I checked"
 * expressible at all.
 *
 * **What is stored is flat text.** A variable holds a `String` on disk, and a
 * consumer that wants a number converts it, the same way every other loosely-typed
 * value enters the graph. A *declaration* names a
 * [io.github.m1n1m1.easymatic.domain.model.config.ValueType], but that is not a second
 * type system beside [io.github.m1n1m1.easymatic.domain.model.schema.ItemSchema] — it is
 * the same one `transform.convert` already offers, and it governs what may be
 * *connected*, never what is *stored*.
 *
 * Both methods take a **ref spec**
 * ([io.github.m1n1m1.easymatic.domain.model.VariableRef]), not a bare name: which
 * variable a node means is a workflow-scoped question, and the implementation the
 * executor hands out is bound to one workflow's declarations.
 *
 * [get] is synchronous because `value.variable` reads it on the pull side, where
 * a read must be cheap and cannot fail. [set] returns immediately and persists
 * in the background for the same reason its caller is an action: the write is
 * the side effect, and waiting on the disk would stall the macro behind it. What
 * it *does* return is whether the write was allowed at all — see [VariableWrite].
 */
interface Variables {

    /** The value of [ref], or null when it is unset, undeclared or unreadable. */
    fun get(ref: String): String?

    /**
     * Sets [ref] to [value], notifying `trigger.variable_change` when this
     * actually changes it, and says whether the write happened.
     */
    fun set(ref: String, value: String): VariableWrite
}

/**
 * What became of a write.
 *
 * An enum rather than a `Boolean` because the two refusals send the user to two
 * different places — "that one is a constant, change the declaration or write to
 * something else" and "nothing is declared under that reference any more, re-point
 * the node" — and a boolean cannot carry which. Not a sealed class either: there is
 * nothing to carry beyond the reason.
 *
 * A refusal is a *runtime* disappointment, not structural invalidity: the action
 * logs it at WARN and pulses `out` anyway, the same stance `transform.json_read`
 * takes when a path does not match.
 */
enum class VariableWrite {
    /** The value was stored (or was already what it is being set to). */
    STORED,

    /** The reference names a constant, whose value is fixed by its declaration. */
    REFUSED_CONSTANT,

    /** Nothing is declared under that reference — blank, or a declaration since deleted. */
    REFUSED_UNDECLARED,
}

/**
 * A store with nothing in it, for engine-only tests and previews. Every read is
 * null and every write is dropped, so a value node built on it contributes no
 * item and a comparison over it fails closed — the same contract
 * [UnknownDeviceState] keeps for the device properties.
 */
object NoVariables : Variables {
    override fun get(ref: String): String? = null
    override fun set(ref: String, value: String): VariableWrite = VariableWrite.REFUSED_UNDECLARED
}
