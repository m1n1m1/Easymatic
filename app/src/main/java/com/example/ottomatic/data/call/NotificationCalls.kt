package com.example.ottomatic.data.call

import android.app.Notification
import android.app.Person
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.StatusBarNotification
import com.example.ottomatic.data.notification.NotificationMessages

/**
 * Reads a posted notification as a phone call, or answers null when it is not one.
 *
 * The mirror image of [NotificationMessages], and deliberately so: that one drops
 * `sbn.isOngoing` because for a messenger an in-progress call is "a status, not an
 * arrival", and this is exactly the population that line discards. The two read the same
 * stream and refuse each other's notifications, which is why neither needed a change to
 * the other when this arrived.
 *
 * There is no "is this a calling app?" list here, and there deliberately never will be,
 * for the reason [NotificationMessages] gives: a hard-coded set of packages is an
 * integration that breaks the day somebody installs the fifth one. What identifies a call
 * is the *shape* of the notification, and two shapes qualify:
 *
 * - a **`CallStyle`**, which is the API Android added for exactly this in API 31 and
 *   which carries who is calling, whether it is incoming, and whether it is video; or
 * - **`CATEGORY_CALL`** on a notification that is either ongoing or carries a
 *   full-screen intent, which is what the same apps do below API 31 and what an app that
 *   never adopted `CallStyle` still does.
 *
 * Both bars are deliberately high. `CATEGORY_CALL` alone is not enough: a call app posts
 * that on call history and voicemail too, and a macro that fired on those would be firing
 * on a record of a call rather than on a call.
 *
 * The **full-screen intent is what tells ringing from connected** on the older path, and
 * it is the one signal that is neither locale-dependent nor app-specific — a full-screen
 * intent exists to throw up an incoming-call screen, so a notification carrying one is
 * announcing a call rather than reporting one in progress. On the `CallStyle` path the
 * call type says so outright and nothing has to be inferred.
 */
object NotificationCalls {

    /** [sbn] as a call, or null. */
    @Suppress("ReturnCount") // Not a notification, a group summary, or neither shape.
    fun read(sbn: StatusBarNotification, packageManager: PackageManager): ParsedCall? {
        val notification = sbn.notification ?: return null
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        return callStyle(notification, sbn, packageManager)
            ?: categoryCall(notification, sbn, packageManager)
    }

    /** The API 31 path, where the platform answers every question outright. */
    @Suppress("ReturnCount") // A guard chain: too old, no extras, not this template.
    private fun callStyle(
        notification: Notification,
        sbn: StatusBarNotification,
        packageManager: PackageManager,
    ): ParsedCall? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val extras = notification.extras ?: return null
        if (extras.getString(Notification.EXTRA_TEMPLATE) != CALL_STYLE_TEMPLATE) return null
        val callType = extras.getInt(Notification.EXTRA_CALL_TYPE, CALL_TYPE_UNKNOWN)
        return ParsedCall(
            packageName = sbn.packageName,
            appName = NotificationMessages.appNameOf(sbn.packageName, packageManager),
            caller = personName(extras) ?: title(extras),
            // Screening counts as ringing: the call is being announced and nobody has
            // picked it up, which is the only distinction this app makes.
            incoming = callType != CALL_TYPE_ONGOING,
            connected = callType == CALL_TYPE_ONGOING,
            video = extras.getBoolean(Notification.EXTRA_CALL_IS_VIDEO, false),
        )
    }

    /** The path that carries every older phone, and every app that never adopted the API. */
    @Suppress("ReturnCount") // Not the category, or not live: two different refusals.
    private fun categoryCall(
        notification: Notification,
        sbn: StatusBarNotification,
        packageManager: PackageManager,
    ): ParsedCall? {
        if (notification.category != Notification.CATEGORY_CALL) return null
        val ringing = notification.fullScreenIntent != null
        // Neither ongoing nor announcing: this is a record of a call, not a call.
        if (!ringing && !sbn.isOngoing) return null
        return ParsedCall(
            packageName = sbn.packageName,
            appName = NotificationMessages.appNameOf(sbn.packageName, packageManager),
            caller = title(notification.extras),
            incoming = ringing,
            connected = !ringing,
            video = false,
        )
    }

    /**
     * The caller as the `CallStyle` names them.
     *
     * Guarded on API 31 by its only caller, which is well above [Person]'s own 28, so the
     * lint suppression is about the compiler not being able to see through that.
     */
    // Every exit is "nobody named", by a different route: too old a phone, no person in
    // the bundle, or a person with no name on them.
    @Suppress("DEPRECATION", "ReturnCount")
    private fun personName(extras: android.os.Bundle): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val person = runCatching {
            extras.getParcelable(Notification.EXTRA_CALL_PERSON) as? Person
        }.getOrNull() ?: return null
        return person.name?.toString()?.takeIf { it.isNotBlank() }
    }

    /**
     * The notification's title, which on a call notification is the caller.
     *
     * The fallback everywhere, and on the older path the only answer there is — which
     * costs nothing, because an app with no `CallStyle` to fill in still has to print who
     * is calling somewhere a person can read it.
     */
    private fun title(extras: android.os.Bundle?): String =
        extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()

    /** What `Notification.EXTRA_TEMPLATE` holds for a `CallStyle`. */
    private const val CALL_STYLE_TEMPLATE = "android.app.Notification\$CallStyle"

    private const val CALL_TYPE_UNKNOWN = 0
    private const val CALL_TYPE_ONGOING = 2
}
