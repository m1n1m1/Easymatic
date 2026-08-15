package com.example.ottomatic.core.permissions

/**
 * The kind of grant a [PermissionRequirement] needs. Most permissions use the
 * standard [RUNTIME] dialog; some require a dedicated system flow.
 */
enum class PrerequisiteType {
    /** Standard `requestPermissions` runtime dialog. */
    RUNTIME,

    /** DND policy access — granted on the system Settings page. */
    NOTIFICATION_POLICY,

    /** Notification listener access — granted on the system Settings page. */
    NOTIFICATION_LISTENER,

    /** Foreground service type (e.g. location/sensor) — declared at runtime. */
    FOREGROUND_SERVICE,

    /** Accessibility service — granted on the system Settings page. */
    ACCESSIBILITY_SERVICE,

    /**
     * Drawing over other apps (`SYSTEM_ALERT_WINDOW`) — granted on the system
     * Settings page. What the dialog nodes need: the engine that runs a macro is
     * a background service, so a window it puts on screen has to survive whatever
     * app the user is actually looking at.
     */
    OVERLAY,

    /** Device administrator — granted via `DeviceAdminReceiver` enabling. */
    DEVICE_ADMIN,

    /**
     * Exemption from battery optimisation. Not a permission the platform gates
     * any single call on: it governs whether the engine survives Doze, and so
     * whether *anything* re-arms after a reboot.
     */
    BATTERY_OPTIMISATION,

    /**
     * Scheduling alarms that fire at an exact time (API 31+, granted by default
     * below that). Without it a schedule still runs, but the platform is free to
     * batch it — which is why the schedule trigger degrades rather than fails.
     */
    EXACT_ALARM,

    /**
     * Writing system settings (`Settings.System.canWrite`). What brightness,
     * screen timeout and auto-rotate need; each of them returns "did nothing"
     * without it.
     */
    WRITE_SETTINGS,

    /**
     * The NFC radio being switched on.
     *
     * The odd one out, and worth reading twice: `android.permission.NFC` is an
     * install-time permission that is always held, so there is nothing here that
     * a *permission* check would ever find wanting. What actually decides whether
     * a tag trigger can fire is a system-wide toggle with its own Settings page —
     * which is exactly the shape this enum already models, and the reason it is
     * modelled here rather than as a check hidden inside the trigger.
     *
     * An app cannot turn it on itself: `NfcAdapter.enable()` is system-only. So
     * unlike every runtime permission there is no dialog to raise, only a page to
     * open — and unlike the other entries here, the thing may not exist at all on
     * this phone. See `AndroidPermissionChecker` for what that answers.
     */
    NFC,
}

/**
 * A permission prerequisite attached to a
 * [com.example.ottomatic.domain.model.NodeTypeDefinition].
 *
 * Each trigger/action declares the permissions it needs so the editor and the
 * [com.example.ottomatic.engine.WorkflowRunner] can verify them before a macro
 * is armed. [manifestPermission] is the `android.permission.*` name (or null
 * for non-runtime prerequisites such as accessibility); [type] selects the
 * grant flow the UI must drive.
 *
 * [rationaleKey] is a key the UI layer maps to a localised rationale string.
 */
data class PermissionRequirement(
    val manifestPermission: String?,
    val type: PrerequisiteType,
    val rationaleKey: String = "",
) {
    /**
     * A stable identity for this prerequisite, so which ones are satisfied can be
     * published as plain strings for anything to read.
     *
     * Two nodes needing the same grant must produce the same key, which is why a
     * [PrerequisiteType.RUNTIME] one is keyed by its manifest name — there are
     * many of those — while the rest are keyed by type, since each is a single
     * system-wide switch. See `GrantedPrerequisites`.
     */
    val key: String
        get() = if (type == PrerequisiteType.RUNTIME) manifestPermission.orEmpty() else type.name

    /**
     * What to call this in a sentence aimed at the user.
     *
     * Lives here rather than beside the editor's rationale strings because the
     * *validator* needs it too, and that runs in `engine` where no UI is
     * reachable. The two are different registers on purpose: a rationale is a
     * paragraph explaining why a node is asking, this is a noun phrase that fits
     * inside "'Launch App' needs ___".
     *
     * The fallback is the manifest name's last segment — ugly, but it only shows
     * for a permission no node declares yet, and naming it wrongly would be worse
     * than naming it bluntly.
     */
    val label: String
        get() = when {
            type == PrerequisiteType.OVERLAY -> "permission to draw over other apps"
            type == PrerequisiteType.NOTIFICATION_LISTENER -> "notification access"
            type == PrerequisiteType.NOTIFICATION_POLICY -> "Do Not Disturb access"
            type == PrerequisiteType.ACCESSIBILITY_SERVICE -> "accessibility access"
            type == PrerequisiteType.BATTERY_OPTIMISATION ->
                "an exemption from battery optimisation"
            type == PrerequisiteType.EXACT_ALARM -> "permission to set exact alarms"
            type == PrerequisiteType.WRITE_SETTINGS -> "permission to change system settings"
            type == PrerequisiteType.NFC -> "NFC turned on"
            type != PrerequisiteType.RUNTIME -> "a system permission"
            manifestPermission == Permissions.ACCESS_FINE_LOCATION.manifest -> "location access"
            manifestPermission == Permissions.ACCESS_COARSE_LOCATION.manifest -> "location access"
            manifestPermission == Permissions.ACCESS_BACKGROUND_LOCATION.manifest ->
                "location access set to \"Allow all the time\""
            manifestPermission == Permissions.CALL_PHONE.manifest -> "permission to make calls"
            manifestPermission == Permissions.SEND_SMS.manifest -> "permission to send texts"
            manifestPermission == Permissions.RECEIVE_SMS.manifest -> "permission to receive texts"
            manifestPermission == Permissions.READ_CONTACTS.manifest -> "contacts access"
            manifestPermission == Permissions.READ_CALENDAR.manifest -> "calendar access"
            manifestPermission == Permissions.WRITE_CALENDAR.manifest ->
                "permission to change your calendar"
            manifestPermission == Permissions.POST_NOTIFICATIONS.manifest ->
                "permission to post notifications"
            else -> manifestPermission.orEmpty().substringAfterLast('.')
        }
}
