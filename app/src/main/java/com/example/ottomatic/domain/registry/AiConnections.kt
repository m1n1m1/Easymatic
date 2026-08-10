package com.example.ottomatic.domain.registry

/**
 * Which AI connections exist, as a lookup anything in `domain` may reach.
 *
 * The fourth of the hydrated registries, after [MacroDirectory], [GlobalVariables]
 * and [SmartHomeHubs], published for their reason: `GraphValidator` has to answer
 * "does the connection this node points at still exist?", it runs on every
 * keystroke in the editor, and the only thing that knows is
 * `AiConnectionRepository` — which lives in `data`. Neither an injected repository
 * nor a suspension point is available where the question is asked.
 *
 * The argument for asking is [SmartHomeHubs]', slightly weaker and still worth it:
 * an Ask AI node whose connection was deleted renders almost perfectly — the field
 * falls back to "Deleted connection" where a light node's would still read "Kitchen
 * ceiling" — but the node itself looks entirely ordinary on the canvas, and a macro
 * that quietly stops answering is the failure the Problems panel exists for.
 *
 * [isHydrated] carries [MacroDirectory]'s reasoning unchanged: an unhydrated
 * registry answers "not found" to everything, and without the guard a validator
 * running in a process that has never listed connections would report every AI node
 * in every macro as dangling. Empty and unasked are different states.
 */
object AiConnections {

    @Volatile
    private var current: Set<String>? = null

    /** Whether anything has published a list yet; see the class KDoc. */
    val isHydrated: Boolean get() = current != null

    /** Whether [connectionId] names a connection that is still set up on this device. */
    fun exists(connectionId: String): Boolean = current?.contains(connectionId) == true

    /** Publishes [connectionIds] as the current set. Called as the connection library emits. */
    fun hydrate(connectionIds: Collection<String>) {
        current = connectionIds.toSet()
    }

    /** Returns to the unhydrated state. Test seam, mirroring [MacroDirectory.reset]. */
    internal fun reset() {
        current = null
    }
}
