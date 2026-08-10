package com.example.ottomatic.domain.registry

/**
 * Which smart-home hubs exist, as a lookup anything in `domain` may reach.
 *
 * The sibling of [MacroDirectory] and [GlobalVariables], published for their reason:
 * `GraphValidator` has to answer "does the hub this node points at still exist?", it
 * runs on every keystroke in the editor, and the only thing that knows is
 * `SmartHomeHubRepository` — which lives in `data`. Neither an injected repository
 * nor a suspension point is available where the question is asked.
 *
 * The argument for asking it at all is stronger here than for a macro. A light node
 * whose hub has been removed renders **perfectly** — the name it was given is cached
 * inside the reference itself, so the field still reads "Kitchen ceiling" — and then
 * does nothing at all. That is exactly the "looks fine from outside" failure the
 * Problems panel exists for.
 *
 * [isHydrated] carries [MacroDirectory]'s reasoning unchanged: an unhydrated registry
 * answers "not found" to everything, and without the guard a validator running in a
 * process that has never listed hubs would report every light node in every macro as
 * dangling. Empty and unasked are different states.
 */
object SmartHomeHubs {

    @Volatile
    private var current: Set<String>? = null

    /** Whether anything has published a list yet; see the class KDoc. */
    val isHydrated: Boolean get() = current != null

    /** Whether [hubId] names a hub that is still set up on this device. */
    fun exists(hubId: String): Boolean = current?.contains(hubId) == true

    /** Publishes [hubIds] as the current set. Called as the hub library emits. */
    fun hydrate(hubIds: Collection<String>) {
        current = hubIds.toSet()
    }

    /** Returns to the unhydrated state. Test seam, mirroring [MacroDirectory.reset]. */
    internal fun reset() {
        current = null
    }
}
