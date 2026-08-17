package com.example.ottomatic.sample

import android.content.Context

/**
 * The sample's stand-in for an account.
 *
 * A real plugin stores an OAuth token, and stores it **in its own app**, under its own
 * uid, with whatever protection it thinks right. That is not a limitation of the plugin
 * API but the point of it: Ottomatic withholds `@ApiToken` and its credential libraries
 * from plugins because those are the host's trust boundary, and a plugin that kept its
 * user's credentials inside Ottomatic would be asking the host to hold something it
 * cannot verify, refresh or revoke.
 *
 * What Ottomatic *does* provide, and did not until protocol 2, is a way to reach the
 * screen where the sign-in happens — see [SampleSettingsActivity] — and a way for the
 * plugin to say it has not happened yet, which is `BaseOttomaticPluginService.status()`.
 */
object SampleAccount {

    private const val PREFS = "sample_account"
    private const val KEY_SIGNED_IN = "signed_in"

    fun isSignedIn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SIGNED_IN, false)

    fun setSignedIn(context: Context, signedIn: Boolean) {
        prefs(context).edit().putBoolean(KEY_SIGNED_IN, signedIn).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
