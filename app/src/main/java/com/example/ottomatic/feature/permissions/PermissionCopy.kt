package com.example.ottomatic.feature.permissions

import androidx.annotation.StringRes
import com.example.ottomatic.R
import com.example.ottomatic.core.permissions.PermissionRequirement
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.core.permissions.PrerequisiteType

/**
 * The user-facing words for a permission — three of them, because the two places
 * that talk about permissions are asking different questions.
 *
 * [rationaleRes] is keyed by [PermissionRequirement.rationaleKey]: *why is this node
 * asking?* `overlay.dialog` and `overlay.launch` are the same system switch with two
 * different reasons, and they are two constants precisely so a node's card can say
 * which one applies to it.
 *
 * [titleRes] and [descriptionRes] are keyed by [PermissionRequirement.key]: *what is
 * this switch, and what does the app do with it?* On the permissions screen those two
 * overlay reasons collapse into a single row, so its text has to cover the grant
 * rather than any one node's use of it.
 *
 * None of the three is [PermissionRequirement.label], which has its own written
 * contract — a noun phrase that fits inside "'Launch App' needs ___" — and is
 * consumed by `GraphValidator` to build exactly that sentence.
 *
 * These return **resource ids rather than strings**, which is the direct route: the
 * answer set is a closed enum, so an exhaustive `when` is checked by the compiler in
 * both directions and a missing string fails the build. It also keeps the whole file
 * pure, so `PermissionCopyTest` can go on asserting over it with no device — and
 * asserting that two ids differ is a stronger statement than that two strings do.
 *
 * Null means *no words for this one yet*. Only the unknown-permission fallbacks can
 * produce it for a title or description; a rationale is nullable by design.
 */

/** A short Title Case noun for the row. */
@StringRes
internal fun titleRes(requirement: PermissionRequirement): Int? =
    when (requirement.type) {
        PrerequisiteType.OVERLAY -> R.string.perm_title_overlay
        PrerequisiteType.NOTIFICATION_LISTENER -> R.string.perm_title_notification_listener
        PrerequisiteType.NOTIFICATION_POLICY -> R.string.perm_title_notification_policy
        PrerequisiteType.ACCESSIBILITY_SERVICE -> R.string.perm_title_accessibility_service
        PrerequisiteType.BATTERY_OPTIMISATION -> R.string.perm_title_battery_optimisation
        PrerequisiteType.EXACT_ALARM -> R.string.perm_title_exact_alarm
        PrerequisiteType.WRITE_SETTINGS -> R.string.perm_title_write_settings
        PrerequisiteType.NFC -> R.string.perm_title_nfc
        PrerequisiteType.MANAGE_MEDIA -> R.string.perm_title_manage_media
        PrerequisiteType.FOREGROUND_SERVICE -> R.string.perm_title_foreground_service
        PrerequisiteType.DEVICE_ADMIN -> R.string.perm_title_device_admin
        PrerequisiteType.RUNTIME -> runtimeTitleRes(requirement.manifestPermission)
    }

@StringRes
@Suppress("CyclomaticComplexMethod") // A flat copy table, not branching logic.
private fun runtimeTitleRes(manifest: String?): Int? = when (manifest) {
    Permissions.ACCESS_FINE_LOCATION.manifest -> R.string.perm_title_access_fine_location
    Permissions.ACCESS_COARSE_LOCATION.manifest -> R.string.perm_title_access_coarse_location
    Permissions.ACCESS_BACKGROUND_LOCATION.manifest -> R.string.perm_title_access_background_location
    Permissions.RECEIVE_SMS.manifest -> R.string.perm_title_receive_sms
    Permissions.SEND_SMS.manifest -> R.string.perm_title_send_sms
    Permissions.CALL_PHONE.manifest -> R.string.perm_title_call_phone
    Permissions.READ_CONTACTS.manifest -> R.string.perm_title_read_contacts
    Permissions.READ_CALENDAR.manifest -> R.string.perm_title_read_calendar
    Permissions.WRITE_CALENDAR.manifest -> R.string.perm_title_write_calendar
    Permissions.POST_NOTIFICATIONS.manifest -> R.string.perm_title_post_notifications
    Permissions.BLUETOOTH_CONNECT.manifest -> R.string.perm_title_bluetooth_connect
    Permissions.CAMERA.manifest -> R.string.perm_title_camera
    Permissions.RECORD_AUDIO.manifest -> R.string.perm_title_record_audio
    // One row for the two names the media read goes by: `Permission.onApi` resolves
    // which applies, but a *declared* requirement always carries the modern one, and
    // the legacy entry is here for the catalogue's own app-level rows.
    Permissions.READ_MEDIA_IMAGES.manifest -> R.string.perm_title_read_media_images
    Permissions.READ_EXTERNAL_STORAGE.manifest -> R.string.perm_title_read_media_images
    Permissions.WRITE_EXTERNAL_STORAGE.manifest -> R.string.perm_title_write_media_images
    Permissions.ACCESS_MEDIA_LOCATION.manifest -> R.string.perm_title_access_media_location
    else -> null
}

/**
 * One sentence saying what Ottomatic does with the grant.
 *
 * Total for everything in the catalogue, unlike [rationaleRes]: a row with no body
 * text is a bug rather than a design choice, because unlike the node card there is no
 * option to render nothing — the row is on screen either way.
 */
@StringRes
@Suppress("CyclomaticComplexMethod") // A flat copy table, not branching logic.
internal fun descriptionRes(requirement: PermissionRequirement): Int? =
    when (requirement.type) {
        PrerequisiteType.OVERLAY -> R.string.perm_desc_overlay
        PrerequisiteType.NOTIFICATION_LISTENER -> R.string.perm_desc_notification_listener
        PrerequisiteType.NOTIFICATION_POLICY -> R.string.perm_desc_notification_policy
        PrerequisiteType.ACCESSIBILITY_SERVICE -> R.string.perm_desc_accessibility_service
        PrerequisiteType.BATTERY_OPTIMISATION -> R.string.perm_desc_battery_optimisation
        PrerequisiteType.EXACT_ALARM -> R.string.perm_desc_exact_alarm
        PrerequisiteType.WRITE_SETTINGS -> R.string.perm_desc_write_settings
        PrerequisiteType.NFC -> R.string.perm_desc_nfc
        PrerequisiteType.MANAGE_MEDIA -> R.string.perm_desc_manage_media
        PrerequisiteType.FOREGROUND_SERVICE -> R.string.perm_desc_foreground_service
        PrerequisiteType.DEVICE_ADMIN -> R.string.perm_desc_device_admin
        PrerequisiteType.RUNTIME -> runtimeDescriptionRes(requirement.manifestPermission)
    }

@StringRes
@Suppress("CyclomaticComplexMethod") // A flat copy table, not branching logic.
private fun runtimeDescriptionRes(manifest: String?): Int = when (manifest) {
    Permissions.ACCESS_FINE_LOCATION.manifest -> R.string.perm_desc_access_fine_location
    Permissions.ACCESS_COARSE_LOCATION.manifest -> R.string.perm_desc_access_coarse_location
    Permissions.ACCESS_BACKGROUND_LOCATION.manifest -> R.string.perm_desc_access_background_location
    Permissions.RECEIVE_SMS.manifest -> R.string.perm_desc_receive_sms
    Permissions.SEND_SMS.manifest -> R.string.perm_desc_send_sms
    Permissions.CALL_PHONE.manifest -> R.string.perm_desc_call_phone
    Permissions.READ_CONTACTS.manifest -> R.string.perm_desc_read_contacts
    Permissions.READ_CALENDAR.manifest -> R.string.perm_desc_read_calendar
    Permissions.WRITE_CALENDAR.manifest -> R.string.perm_desc_write_calendar
    Permissions.POST_NOTIFICATIONS.manifest -> R.string.perm_desc_post_notifications
    Permissions.BLUETOOTH_CONNECT.manifest -> R.string.perm_desc_bluetooth_connect
    Permissions.CAMERA.manifest -> R.string.perm_desc_camera
    Permissions.RECORD_AUDIO.manifest -> R.string.perm_desc_record_audio
    Permissions.READ_MEDIA_IMAGES.manifest -> R.string.perm_desc_read_media_images
    Permissions.READ_EXTERNAL_STORAGE.manifest -> R.string.perm_desc_read_media_images
    Permissions.WRITE_EXTERNAL_STORAGE.manifest -> R.string.perm_desc_write_media_images
    Permissions.ACCESS_MEDIA_LOCATION.manifest -> R.string.perm_desc_access_media_location
    else -> R.string.perm_desc_unknown
}

/**
 * The paragraph shown on a node's own config card, keyed by
 * [PermissionRequirement.rationaleKey].
 *
 * Nullable on purpose, and only for the node card: a node that declares a
 * Settings-granted prerequisite without a rationale gets no card at all, because
 * sending somebody to a system page with no explanation of what to switch on, or
 * why, is worse than saying nothing. The permissions screen never consults this
 * — it has [descriptionRes], which is total.
 */
@StringRes
@Suppress("CyclomaticComplexMethod") // A flat copy table, not branching logic.
internal fun rationaleRes(requirement: PermissionRequirement): Int? =
    when (requirement.rationaleKey) {
        "accessibility.keys" -> R.string.perm_rationale_accessibility_keys
        // One switch, two rationales: what the volume trigger cannot do without it and
        // what this cannot are different sentences, and the card is on the node.
        "screen.capture" -> R.string.perm_rationale_screen_capture
        "camera.photo" -> R.string.perm_rationale_camera_photo
        "audio.record" -> R.string.perm_rationale_audio_record
        "notification.listener" -> R.string.perm_rationale_notification_listener
        "dnd.policy" -> R.string.perm_rationale_dnd_policy
        "overlay.dialog" -> R.string.perm_rationale_overlay_dialog
        "nfc.radio" -> R.string.perm_rationale_nfc_radio
        "alarm.exact" -> R.string.perm_rationale_alarm_exact
        "overlay.launch" -> R.string.perm_rationale_overlay_launch
        "calendar.read" -> R.string.perm_rationale_calendar_read
        "calendar.write" -> R.string.perm_rationale_calendar_write
        "media.read" -> R.string.perm_rationale_media_read
        "media.write" -> R.string.perm_rationale_media_write
        "media.location" -> R.string.perm_rationale_media_location
        "media.manage" -> R.string.perm_rationale_media_manage
        "overlay.media" -> R.string.perm_rationale_overlay_media
        // The third rationale on the notification-access switch, beside
        // "notification.listener": reading a message somebody sent and reaching the
        // player that is running are different sentences, and the card is on the node.
        "media.playback" -> R.string.perm_rationale_media_playback
        else -> null
    }

/**
 * The last-resort name for a permission nobody has written words for.
 *
 * Structurally English-only, and deliberately kept anyway: it shows only for a
 * permission that has reached the catalogue without copy, where a blunt accurate
 * rendering of the manifest name beats a generic "Other permission" that says
 * nothing at all. `PermissionCopyTest` asserts nothing in the catalogue needs it.
 */
internal fun derivedTitle(manifest: String?): String =
    manifest.orEmpty().substringAfterLast('.').lowercase()
        .replace('_', ' ')
        .replaceFirstChar { it.uppercase() }
