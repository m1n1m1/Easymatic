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

    /**
     * A text-to-speech engine, with at least one usable voice.
     *
     * Android ships one on virtually every phone, so this is the rarer kind of
     * capability: the one that is almost always present and occasionally, silently,
     * is not — a stripped OEM image, a region where no voice data was bundled, a
     * user who uninstalled the engine. The failure it prevents is the quietest in
     * the whole family, because `TextToSpeech.speak` answers `SUCCESS` for an
     * utterance that is queued against an engine that never initialised, and the
     * phone simply stays silent.
     *
     * **It is the capability that made [CapabilityStatus.UNKNOWN] earn its keep a
     * second time.** An engine announces itself only through an asynchronous init
     * callback, so before the first `speak` in a process there is genuinely no
     * answer — and badging every Speak node until somebody used one would warn
     * about hardware that works. `AndroidSpeech` republishes once init resolves,
     * exactly as `OttomaticAccessibilityService` does on connect.
     */
    SPEECH_SYNTHESIS,

    /**
     * A speech recognition service that can turn speech into text.
     *
     * Definitive in a way [SPEECH_SYNTHESIS] is not — `SpeechRecognizer`
     * .`isRecognitionAvailable` is a synchronous package-manager query — so this
     * one answers [CapabilityStatus.AVAILABLE] or [CapabilityStatus.UNAVAILABLE]
     * from the first moment it is asked.
     *
     * It is genuinely absent on more phones than the synthesis half: recognition
     * is normally supplied by the Google app, so a device built without Play
     * services has none, and no setting anywhere will produce one. That is the
     * textbook shape for a capability rather than a prerequisite — there is no
     * page to send the user to.
     */
    SPEECH_RECOGNITION,
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
            SPEECH_SYNTHESIS -> "a text-to-speech engine"
            SPEECH_RECOGNITION -> "speech recognition"
        }
}
