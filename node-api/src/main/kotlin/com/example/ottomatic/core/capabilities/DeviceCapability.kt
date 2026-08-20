package com.example.ottomatic.core.capabilities

/**
 * A piece of hardware, or a platform behaviour over hardware, that a node needs and
 * that **this particular phone may simply not have**.
 *
 * The second axis beside
 * [com.example.ottomatic.core.permissions.PrerequisiteType], and the distinction
 * between them is the whole reason this exists rather than being a thirteenth member
 * of that enum: **a prerequisite is something the user can go and fix, and a
 * capability is a fact about the phone.** `AndroidPermissionChecker` makes that call
 * explicitly today — a device with no NFC chip is reported *satisfied*, because the
 * Permissions screen asks "what does the app need and what has it got?", and "go and
 * switch on hardware you do not have" is not an answer to it; it is a row that can
 * never go green, on a page whose whole job is telling you what to fix.
 *
 * So a capability is deliberately invisible to `PermissionCatalogue` and the
 * Permissions screen, and visible only where the *node* is: the Problems panel, the
 * node's badge, and the workflow list's count. Missing hardware is a statement about
 * one node in one macro on one phone, not about the app.
 *
 * Declaring one costs a line on the node definition and nothing else — the validator
 * walks the declarations, so there is no second registration, exactly as with
 * permissions.
 */
enum class DeviceCapability {
    /**
     * A fingerprint reader whose driver reports **swipes** across it, not merely
     * touches.
     *
     * `FingerprintGestureController` is the only API for this and it is worth knowing
     * how narrow it is: the platform delivers gestures from a physical, usually
     * rear-mounted reader, and in-display optical and ultrasonic sensors generally
     * report nothing at all. So `PackageManager.FEATURE_FINGERPRINT` is *not* the
     * question — most phones pass that and still cannot do this. The answer comes from
     * `isGestureDetectionAvailable`, which is why this capability is the one that
     * forced [CapabilityStatus.UNKNOWN] to exist: only a bound accessibility service
     * can ask it.
     */
    FINGERPRINT_GESTURES,
    ;

    /**
     * What to call this in a sentence aimed at the user — a noun phrase that fits
     * inside "'Fingerprint Gesture' needs ___".
     *
     * Lives here rather than beside the editor's strings for exactly the reason
     * [com.example.ottomatic.core.permissions.PermissionRequirement.label] does: the
     * *validator* needs it, and that runs in `engine` where no UI is reachable.
     */
    val label: String
        get() = when (this) {
            FINGERPRINT_GESTURES -> "a fingerprint sensor that reports swipes"
        }
}
