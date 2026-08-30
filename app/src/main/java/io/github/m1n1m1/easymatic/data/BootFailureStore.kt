package io.github.m1n1m1.easymatic.data

import android.content.Context
import androidx.core.content.edit

/**
 * Tracks whether a background engine start failed and the user should be prompted
 * to disable battery optimisation.
 *
 * **Optimistic pattern**: [io.github.m1n1m1.easymatic.data.trigger.BootReceiver] marks
 * pending *before* attempting to start the foreground service; the service
 * clears it in `onCreate` once it has actually started. If the start throws
 * (Android 12+ background FGS restriction) or the service is killed before
 * `onCreate` runs (OEM battery management), the flag remains set and the next
 * app launch surfaces a battery-optimisation prompt via
 * [io.github.m1n1m1.easymatic.MainActivity].
 *
 * Backed by SharedPreferences so it survives the process death that is the
 * failure mode being detected.
 */
object BootFailureStore {

    private const val PREFS = "easymatic.boot_failure"
    private const val KEY_PENDING = "needs_battery_prompt"

    fun markPending(context: Context) {
        prefs(context).edit { putBoolean(KEY_PENDING, true) }
    }

    fun clear(context: Context) {
        prefs(context).edit { putBoolean(KEY_PENDING, false) }
    }

    /**
     * Reads and clears the flag. Returns true if a prompt should be shown.
     * Callers should only show the prompt when this returns true AND a macro is
     * actually enabled (so we don't prompt about a failure to arm nothing).
     */
    fun consume(context: Context): Boolean {
        val v = prefs(context).getBoolean(KEY_PENDING, false)
        if (v) clear(context)
        return v
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
