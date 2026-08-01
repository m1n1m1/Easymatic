package com.example.ottomatic.core.service

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
 * Deliberately flat text: a variable holds a `String`, and a consumer that wants
 * a number converts it visibly, the same way every other loosely-typed value
 * enters the graph. Typed variables would need a second type system alongside
 * [com.example.ottomatic.domain.model.schema.ItemSchema] that only variables
 * used.
 *
 * [get] is synchronous because `value.variable` reads it on the pull side, where
 * a read must be cheap and cannot fail. [set] returns immediately and persists
 * in the background for the same reason its caller is an action: the write is
 * the side effect, and waiting on the disk would stall the macro behind it.
 */
interface Variables {

    /** The value of [name], or null when it has never been set. */
    fun get(name: String): String?

    /**
     * Sets [name] to [value], notifying `trigger.variable_change` when this
     * actually changes it.
     */
    fun set(name: String, value: String)
}

/**
 * A store with nothing in it, for engine-only tests and previews. Every read is
 * null and every write is dropped, so a value node built on it contributes no
 * item and a comparison over it fails closed — the same contract
 * [UnknownDeviceState] keeps for the device properties.
 */
object NoVariables : Variables {
    override fun get(name: String): String? = null
    override fun set(name: String, value: String) = Unit
}
