package io.github.m1n1m1.easymatic.domain.registry

import io.github.m1n1m1.easymatic.domain.model.SmartHomeResource

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
    private var current: Map<String, List<SmartHomeResource>>? = null

    /** Whether anything has published a list yet; see the class KDoc. */
    val isHydrated: Boolean get() = current != null

    /** Whether [hubId] names a hub that is still set up on this device. */
    fun exists(hubId: String): Boolean = current?.containsKey(hubId) == true

    /** Every hub's id, in the order the library emitted them. */
    fun ids(): List<String> = current?.keys?.toList().orEmpty()

    /**
     * The cached lights, groups and scenes on [hubId], or on **every** hub when it is
     * blank.
     *
     * Published so [PickerOptions] can offer them — nothing in `domain` could
     * enumerate a scene before this, which is why a light field on a tool had to be
     * pinned by the author and could never be left for the model to choose. The
     * snapshot already sat on the hub (`SmartHomeHub.resources`); only the *registry*
     * was narrower than what it had.
     *
     * [HaCatalog]'s shape exactly, and for its reason: a projection of the library
     * that carries no credential, so `domain` can answer a question about a hub
     * without being able to reach one.
     */
    fun resources(hubId: String = ""): List<SmartHomeResource> {
        val byHub = current ?: return emptyList()
        return if (hubId.isBlank()) byHub.values.flatten() else byHub[hubId].orEmpty()
    }

    /** The hub holding [rid], for turning a bare resource back into a full reference. */
    fun hubOf(rid: String): String? =
        current?.entries?.firstOrNull { (_, resources) -> resources.any { it.rid == rid } }?.key

    /** Publishes the library. Called as the hub repository emits. */
    fun hydrate(byHub: Map<String, List<SmartHomeResource>>) {
        current = byHub
    }

    /** Returns to the unhydrated state. Test seam, mirroring [MacroDirectory.reset]. */
    internal fun reset() {
        current = null
    }
}
