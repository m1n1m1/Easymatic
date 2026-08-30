package io.github.m1n1m1.easymatic.data.call

import android.content.Context
import android.telecom.TelecomManager
import androidx.core.content.getSystemService
import io.github.m1n1m1.easymatic.core.trigger.CallPayload
import io.github.m1n1m1.easymatic.data.notification.NotificationMessages

/**
 * The process-wide [CallSessionTracker].
 *
 * A process-wide `object` on `ActiveNotifications`' reasoning: a
 * `NotificationListenerService` is constructed by the *system* and can never be handed a
 * dependency, and a manifest-registered `BroadcastReceiver` is in the same position. Both
 * feed this, and they have to feed the same instance or the two halves of a call would
 * never meet.
 *
 * It holds no `PendingIntent`s, unlike `ActiveNotifications`, because nothing here
 * answers or hangs up a call — only watches. It is still bounded, because it is fed by
 * other apps' notifications.
 *
 * The state machine itself lives in [CallSessionTracker], which is a plain class with a
 * JVM test over it. This object is the singleton and nothing else.
 */
object CallSessions {

    private val tracker = CallSessionTracker()

    /** See [CallSessionTracker.onTelephony]. */
    fun onTelephony(state: String, nowMs: Long, dialerPackage: String, dialerAppName: String) =
        tracker.onTelephony(state, nowMs, dialerPackage, dialerAppName)

    /** See [CallSessionTracker.onCallNotification]. */
    fun onCallNotification(key: String, call: ParsedCall, nowMs: Long, dialerPackage: String) =
        tracker.onCallNotification(key, call, nowMs, dialerPackage)

    /** See [CallSessionTracker.onNotificationGone]. */
    fun onNotificationGone(key: String, nowMs: Long) = tracker.onNotificationGone(key, nowMs)

    /** See [CallSessionTracker.forgetAppCalls]. */
    fun forgetAppCalls() = tracker.forgetAppCalls()

    /** The call in progress, or null. */
    fun current(): CallSnapshot? = tracker.current()
}

/**
 * This transition as a bus payload, written once for both producers.
 *
 * The keys come from [CallPayload] in `core`, which the triggers read back through — so
 * the contract is a shared declaration rather than a pair of hand-copied string lists.
 */
fun CallTransition.toPayload(): Map<String, String> = mapOf(
    CallPayload.KEY_STATE to when (phase) {
        CallPhase.RINGING -> CallPayload.STATE_RINGING
        CallPhase.ACTIVE -> CallPayload.STATE_ACTIVE
        CallPhase.ENDED -> CallPayload.STATE_ENDED
    },
    CallPayload.KEY_CALLER to caller,
    CallPayload.KEY_APP_NAME to appName,
    CallPayload.KEY_PACKAGE to packageName,
    CallPayload.KEY_INCOMING to incoming.toString(),
    CallPayload.KEY_VIDEO to video.toString(),
    CallPayload.KEY_ANSWERED to answered.toString(),
    CallPayload.KEY_DURATION_SECONDS to durationSeconds.toString(),
    CallPayload.KEY_TIMESTAMP to atMs.toString(),
)

/**
 * Which app this phone currently treats as its dialer.
 *
 * The one place a package name is consulted anywhere in the call integration, and it is
 * not a vendor list: the question asked is "which app did *this* phone's owner make their
 * dialer?", which is as true of a replacement dialer as of the one the phone shipped
 * with. `CallSessions` needs the answer so a cellular call's own notification joins the
 * session telephony already opened instead of being counted as a second call.
 *
 * `getDefaultDialerPackage` needs no permission. It answers null on a device with no
 * telephony at all, which is exactly the device where nothing will ever join a cellular
 * session anyway.
 */
object DefaultDialer {

    /** The dialer's package name, or the empty string when there is none. */
    fun packageName(context: Context): String = runCatching {
        context.getSystemService<TelecomManager>()?.defaultDialerPackage.orEmpty()
    }.getOrDefault("")

    /** The dialer's own name for itself, for a session no notification will ever fill in. */
    fun appName(context: Context, packageName: String): String =
        if (packageName.isEmpty()) {
            ""
        } else {
            NotificationMessages.appNameOf(packageName, context.packageManager)
        }
}
