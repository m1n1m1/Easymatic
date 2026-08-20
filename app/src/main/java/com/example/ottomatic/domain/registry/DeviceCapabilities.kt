package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.capabilities.CapabilityChecker
import com.example.ottomatic.core.capabilities.CapabilityStatus
import com.example.ottomatic.core.capabilities.DeviceCapability

/**
 * Which [DeviceCapability] this phone actually has, as a lookup anything in `domain`
 * may reach.
 *
 * The fourth sibling of [GlobalVariables], [MacroDirectory] and
 * [GrantedPrerequisites], published for the same reason and answering the same shape
 * of question: `GraphValidator` has to decide whether a node will be able to do its
 * job, it runs on every keystroke in the editor, and the only thing that knows is
 * Android — reachable from `data` through a [CapabilityChecker], which is neither
 * injectable nor suspendable at the point the question is asked.
 *
 * It is a *separate* registry from [GrantedPrerequisites] rather than more entries in
 * it, because the two answer different questions and feed different surfaces. A
 * missing grant is something the user can go and fix, and it belongs on the
 * Permissions screen. Missing hardware is a fact about the phone, and a Permissions
 * row for it could never go green — which is exactly why `AndroidPermissionChecker`
 * reports a phone with no NFC chip as *satisfied*. See [DeviceCapability].
 *
 * [isHydrated] carries the same distinction it does on its three siblings: an
 * unhydrated registry answering "not available" to everything would badge every such
 * node in every macro the moment the engine validated a disk snapshot on boot.
 */
object DeviceCapabilities {

    @Volatile
    private var available: Set<String>? = null

    /** Whether anything has published a set yet; see the class KDoc. */
    val isHydrated: Boolean get() = available != null

    /**
     * Whether [capability] is present on this phone.
     *
     * Answers **true** while unhydrated, on [GrantedPrerequisites.isSatisfied]'s
     * reasoning exactly: the only consumer is a warning, and a panel that cries wolf
     * on a fresh install is worse than one that stays quiet until it knows.
     */
    fun isAvailable(capability: DeviceCapability): Boolean =
        available?.contains(capability.name) ?: true

    /**
     * Reads the current state of every capability any node type declares, and
     * publishes it.
     *
     * Driven from the *declarations* rather than from a fixed list, so a node that
     * starts needing hardware is covered by adding it to that node's definition — the
     * same single-registration rule the node system has everywhere else.
     *
     * [CapabilityStatus.UNKNOWN] lands in the available set, and that is the whole
     * point of the tri-state rather than a lapse. Whether a fingerprint reader reports
     * swipes cannot be asked until the accessibility service is bound, and a phone
     * that nobody has been able to ask must read as silence rather than as a warning.
     * Only a definitive [CapabilityStatus.UNAVAILABLE] warns.
     */
    fun hydrateFrom(checker: CapabilityChecker) {
        available = NodeTypeRegistry.all
            .flatMap { it.capabilities }
            .distinct()
            .filter { checker.status(it) != CapabilityStatus.UNAVAILABLE }
            .mapTo(mutableSetOf()) { it.name }
    }

    /** Publishes [keys] directly. For tests; the app uses [hydrateFrom]. */
    internal fun hydrate(keys: Set<String>) {
        available = keys
    }

    /** Returns to the unhydrated state. Test seam, mirroring [GrantedPrerequisites.reset]. */
    internal fun reset() {
        available = null
    }
}
