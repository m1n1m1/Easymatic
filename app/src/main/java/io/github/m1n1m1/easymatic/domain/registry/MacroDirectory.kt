package io.github.m1n1m1.easymatic.domain.registry

import io.github.m1n1m1.easymatic.domain.model.WorkflowSummary

/**
 * Which macros exist, as a lookup anything in `domain` may reach.
 *
 * The sibling of [GlobalVariables], published for the same reason: `GraphValidator`
 * has to answer "does the macro this node points at still exist?", it runs on every
 * keystroke in the editor, and the only thing that knows is `WorkflowRepository` —
 * which lives in `data` and whose `list()` suspends. Neither an injected repository
 * nor a suspension point is available where the question is asked, so the answer is
 * published here instead and the editor hydrates it when it opens.
 *
 * [isHydrated] is the part worth reading twice. A validator running in a process
 * that has never listed workflows — the executor checking a disk snapshot on boot,
 * say — knows nothing about any macro, and a directory that answered "not found" to
 * everything would report every single macro reference in the graph as dangling.
 * Empty and unasked are different states, and only this flag tells them apart.
 */
object MacroDirectory {

    @Volatile
    private var current: List<WorkflowSummary>? = null

    /** Whether anything has published a list yet; see the class KDoc. */
    val isHydrated: Boolean get() = current != null

    /** Every macro on the device, in the repository's order (by name). */
    fun all(): List<WorkflowSummary> = current.orEmpty()

    /** The macro [id] names, or null when it was deleted or nothing is hydrated. */
    fun byId(id: String): WorkflowSummary? = current?.firstOrNull { it.id == id }

    /** Publishes [macros] as the current set. Called by the graph editor when it opens. */
    fun hydrate(macros: List<WorkflowSummary>) {
        current = macros
    }

    /** Returns to the unhydrated state. Test seam, mirroring [GlobalVariables.reset]. */
    internal fun reset() {
        current = null
    }
}
