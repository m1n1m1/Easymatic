package io.github.m1n1m1.easymatic.core.capabilities

/**
 * What this phone can be said about a [DeviceCapability] right now.
 *
 * A tri-state rather than a `Boolean`, and [UNKNOWN] is the member that earns it.
 * Whether a fingerprint reader reports swipes can only be asked of a **bound**
 * accessibility service, so on a phone that has a reader but has not been given
 * accessibility access there is genuinely no answer yet — and that has to read as
 * silence rather than as a warning, or every such phone gets badged for a question
 * nobody asked. It is the same distinction `MacroDirectory.isHydrated` and
 * `GrantedPrerequisites.isHydrated` draw one level up: empty and unasked are
 * different states.
 */
enum class CapabilityStatus {
    /** The phone can do it. */
    AVAILABLE,

    /** The phone cannot, and no setting will change that. The only state that warns. */
    UNAVAILABLE,

    /** Nobody can tell yet. Reads as silence; see the enum KDoc. */
    UNKNOWN,
}

/**
 * Answers [CapabilityStatus] for a [DeviceCapability].
 *
 * The sibling of [io.github.m1n1m1.easymatic.core.permissions.PermissionChecker], and
 * separate from it for the reason [DeviceCapability] gives: the two answer different
 * questions and feed different surfaces. Implemented in `data`, where Android is
 * reachable; read through `DeviceCapabilities`, which is what `domain` and `engine`
 * see.
 *
 * The default is [CapabilityStatus.UNKNOWN] so a test double or an unbacked host
 * leaves every node silent rather than warning about hardware it never looked at —
 * the same shape `TriggerHost`'s defaults take.
 */
interface CapabilityChecker {
    fun status(capability: DeviceCapability): CapabilityStatus = CapabilityStatus.UNKNOWN
}
