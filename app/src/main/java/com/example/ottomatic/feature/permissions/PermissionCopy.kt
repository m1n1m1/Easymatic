package com.example.ottomatic.feature.permissions

import com.example.ottomatic.core.permissions.PermissionRequirement
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.core.permissions.PrerequisiteType

/**
 * The user-facing words for a permission — three of them, because the two places
 * that talk about permissions are asking different questions.
 *
 * [rationaleFor] is keyed by [PermissionRequirement.rationaleKey]: *why is this
 * node asking?* `overlay.dialog` and `overlay.launch` are the same system switch
 * with two different reasons, and they are two constants precisely so a node's
 * card can say which one applies to it.
 *
 * [titleFor] and [descriptionFor] are keyed by [PermissionRequirement.key]:
 * *what is this switch, and what does the app do with it?* On the permissions
 * screen those two overlay reasons collapse into a single row, so its text has
 * to cover the grant rather than any one node's use of it.
 *
 * None of the three is [PermissionRequirement.label], which has its own written
 * contract — a noun phrase that fits inside "'Launch App' needs ___" — and is
 * consumed by `GraphValidator` to build exactly that sentence.
 */

/** A short Title Case noun for the row. Total. */
internal fun titleFor(requirement: PermissionRequirement): String =
    when (requirement.type) {
        PrerequisiteType.OVERLAY -> "Draw over other apps"
        PrerequisiteType.NOTIFICATION_LISTENER -> "Notification access"
        PrerequisiteType.NOTIFICATION_POLICY -> "Do Not Disturb access"
        PrerequisiteType.ACCESSIBILITY_SERVICE -> "Accessibility access"
        PrerequisiteType.BATTERY_OPTIMISATION -> "Unrestricted battery use"
        PrerequisiteType.EXACT_ALARM -> "Alarms & reminders"
        PrerequisiteType.WRITE_SETTINGS -> "Modify system settings"
        PrerequisiteType.FOREGROUND_SERVICE -> "Foreground service"
        PrerequisiteType.DEVICE_ADMIN -> "Device administrator"
        PrerequisiteType.RUNTIME -> runtimeTitle(requirement.manifestPermission)
    }

private fun runtimeTitle(manifest: String?): String = when (manifest) {
    Permissions.ACCESS_FINE_LOCATION.manifest -> "Location"
    Permissions.ACCESS_COARSE_LOCATION.manifest -> "Approximate location"
    Permissions.ACCESS_BACKGROUND_LOCATION.manifest -> "Location in the background"
    Permissions.RECEIVE_SMS.manifest -> "Receive texts"
    Permissions.SEND_SMS.manifest -> "Send texts"
    Permissions.CALL_PHONE.manifest -> "Make calls"
    Permissions.READ_CONTACTS.manifest -> "Contacts"
    Permissions.POST_NOTIFICATIONS.manifest -> "Notifications"
    Permissions.BLUETOOTH_CONNECT.manifest -> "Bluetooth"
    // Ugly, but it only shows for a permission nothing has words for yet, and a
    // wrong friendly name is worse than a blunt accurate one.
    else -> manifest.orEmpty().substringAfterLast('.').lowercase()
        .replace('_', ' ')
        .replaceFirstChar { it.uppercase() }
}

/**
 * One sentence saying what Ottomatic does with the grant.
 *
 * Total, unlike [rationaleFor]: a row with no body text is a bug rather than a
 * design choice, because unlike the node card there is no option to render
 * nothing — the row is on screen either way.
 */
@Suppress("CyclomaticComplexMethod") // A flat copy table, not branching logic.
internal fun descriptionFor(requirement: PermissionRequirement): String =
    when (requirement.type) {
        PrerequisiteType.OVERLAY ->
            "Lets a macro put a dialog on screen, and open other apps, while you are looking " +
                "at something else. Android blocks both from a background app without it."
        PrerequisiteType.NOTIFICATION_LISTENER ->
            "Lets Ottomatic see notifications from other apps, so a macro can react to one."
        PrerequisiteType.NOTIFICATION_POLICY ->
            "Lets a macro turn Do Not Disturb on and off, and switch the ringer to silent."
        PrerequisiteType.ACCESSIBILITY_SERVICE ->
            "Lets Ottomatic see volume and power button presses. It never reads screen content."
        PrerequisiteType.BATTERY_OPTIMISATION ->
            "Keeps Android from stopping the engine in the background. Without it, macros may " +
                "not re-arm after a reboot and time-based triggers can be delayed."
        PrerequisiteType.EXACT_ALARM ->
            "Lets a schedule fire at the minute you asked for. Without it Android is free to " +
                "batch it with other work, so it may run late."
        PrerequisiteType.WRITE_SETTINGS ->
            "Lets a macro change screen brightness, screen timeout and auto-rotate."
        PrerequisiteType.FOREGROUND_SERVICE ->
            "Lets Ottomatic keep its engine running while your macros are armed."
        PrerequisiteType.DEVICE_ADMIN ->
            "Lets Ottomatic use device administrator features."
        PrerequisiteType.RUNTIME -> runtimeDescription(requirement.manifestPermission)
    }

@Suppress("CyclomaticComplexMethod") // A flat copy table, not branching logic.
private fun runtimeDescription(manifest: String?): String = when (manifest) {
    Permissions.ACCESS_FINE_LOCATION.manifest ->
        "Lets a geofence know when you arrive somewhere or leave it."
    Permissions.ACCESS_COARSE_LOCATION.manifest ->
        "A rougher fix, used when precise location is not available."
    Permissions.ACCESS_BACKGROUND_LOCATION.manifest ->
        "Lets a geofence keep working while Ottomatic is closed — which is the only time it " +
            "is any use. Granted by choosing \"Allow all the time\"."
    Permissions.RECEIVE_SMS.manifest ->
        "Lets a macro run when a text arrives, and read who it is from and what it says."
    Permissions.SEND_SMS.manifest -> "Lets a macro send a text on your behalf."
    Permissions.CALL_PHONE.manifest -> "Lets a macro place a call without you confirming it."
    Permissions.READ_CONTACTS.manifest ->
        "Lets a macro look up the number of a contact you chose. Choosing one needs nothing; " +
            "dialling it later does."
    Permissions.POST_NOTIFICATIONS.manifest ->
        "Lets Ottomatic show a notification — both the ones a macro posts and the one that " +
            "tells you the engine failed to start after a reboot."
    Permissions.BLUETOOTH_CONNECT.manifest ->
        "Lets a macro turn Bluetooth on and off."
    else -> "Used by a node on one of your macros."
}

/**
 * The paragraph shown on a node's own config card, keyed by
 * [PermissionRequirement.rationaleKey].
 *
 * Nullable on purpose, and only for the node card: a node that declares a
 * Settings-granted prerequisite without a rationale gets no card at all, because
 * sending somebody to a system page with no explanation of what to switch on, or
 * why, is worse than saying nothing. The permissions screen never consults this
 * — it has [descriptionFor], which is total.
 */
internal fun rationaleFor(requirement: PermissionRequirement): String? =
    when (requirement.rationaleKey) {
        "accessibility.keys" ->
            "Ottomatic needs accessibility access to see button presses. It never reads screen " +
                "content. If the switch is greyed out, open App info → ⋮ → Allow restricted " +
                "settings first."
        "notification.listener" ->
            "Ottomatic needs notification access to see notifications from other apps."
        "dnd.policy" ->
            "Ottomatic needs Do Not Disturb access to change your ringer mode."
        "overlay.dialog" ->
            "Ottomatic needs permission to draw over other apps so this dialog can reach you " +
                "while you are somewhere else on your phone. Without it the node cancels " +
                "instead of asking."
        "overlay.launch" ->
            "Ottomatic needs permission to draw over other apps so it can open one while you " +
                "are somewhere else on your phone. Android blocks a background app from " +
                "opening another, and without this the node does nothing and says so in the " +
                "console."
        else -> null
    }
