package io.github.m1n1m1.easymatic.data.trigger

import android.app.AlarmManager
import android.app.PendingIntent
import android.os.Build
import android.util.Log

private const val TAG = "ExactAlarms"

/**
 * Whether this device will let the app set an alarm accurate to the minute.
 *
 * `setExact*` needs `SCHEDULE_EXACT_ALARM` from API 31 and **throws** without it,
 * which is why nothing may call the exact variant unguarded.
 */
fun AlarmManager.canScheduleExact(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S || canScheduleExactAlarms()

/**
 * Arms a one-shot wake-up alarm for [atEpochMs], exactly where the platform
 * allows it and inexactly where it does not.
 *
 * Degrading rather than failing is the whole point: without the permission the
 * schedule or the wait still happens, just batched with whatever else Android
 * wanted to run — which is late, but not silent, since the Permissions screen
 * carries the row that explains it.
 *
 * Shared by [AndroidTriggerHost.armAlarm] and
 * [io.github.m1n1m1.easymatic.data.wait.AndroidWaits] so the two cannot drift about
 * which variant to use; [label] only names the caller in the log line.
 */
fun AlarmManager.setWakeup(atEpochMs: Long, pendingIntent: PendingIntent, label: String) {
    if (canScheduleExact()) {
        setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMs, pendingIntent)
    } else {
        Log.w(TAG, "Exact alarms not permitted; $label falls back to an inexact alarm")
        setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMs, pendingIntent)
    }
}
