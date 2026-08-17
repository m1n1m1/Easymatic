package com.example.ottomatic.sample

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The plugin's own settings screen — the way in for an account.
 *
 * Ottomatic offers a button to this from the Plugins screen, having found it by resolving
 * `com.example.ottomatic.action.PLUGIN_SETTINGS` against **this package** through
 * `PackageManager`. It is never launched from an Intent or a component name the plugin
 * sent over the binder, which is the same rule that keeps `Uri` and `PendingIntent` out of
 * the wire entirely: everything crossing the boundary is inert data, and a component name
 * is a thing to launch.
 *
 * Note what this file needs from Ottomatic: **nothing**. No SDK class, no theme, no
 * library. It is an ordinary Activity in an ordinary app, which is the point — a plugin's
 * sign-in is the plugin's business, under its own uid, with its own credentials, and the
 * host's only involvement is knowing that it exists.
 */
class SampleSettingsActivity : Activity() {

    private lateinit var state: TextView
    private lateinit var action: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Sample Tools"

        state = TextView(this)
        action = Button(this).apply {
            setOnClickListener {
                SampleAccount.setSignedIn(context, !SampleAccount.isSignedIn(context))
                render()
            }
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(PADDING, PADDING, PADDING, PADDING)
                addView(state)
                addView(action)
            },
        )
        render()
    }

    private fun render() {
        val signedIn = SampleAccount.isSignedIn(this)
        state.text = if (signedIn) "Signed in." else "Not signed in."
        action.text = if (signedIn) "Sign out" else "Sign in"
    }

    private companion object {
        const val PADDING = 48
    }
}
