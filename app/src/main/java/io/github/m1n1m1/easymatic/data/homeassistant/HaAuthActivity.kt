package io.github.m1n1m1.easymatic.data.homeassistant

import android.app.Activity
import android.os.Bundle

/**
 * Catches the browser's redirect at the end of a Home Assistant sign-in.
 *
 * A trampoline that shows nothing and finishes immediately, wearing
 * `RunTriggerActivity`'s and `NfcTagActivity`'s disguise for their reasons — translucent,
 * `noHistory`, `excludeFromRecents` and an empty `taskAffinity` so it does not drag
 * `MainActivity`'s window into view behind it.
 *
 * **Its own Activity rather than an intent filter on `MainActivity`**, which is the
 * shorter-looking option and is wrong: `MainActivity` is the launcher activity holding
 * the whole Compose back stack, and a `singleTask` relaunch to deliver a redirect would
 * disturb it — dropping the user somewhere other than where they were, mid-setup.
 *
 * It **validates nothing**, deliberately. A custom URI scheme is not exclusive on
 * Android and any app may fire an intent at `easymatic://ha-auth`, so what arrives here
 * is untrusted by construction. The nonce comparison happens where the nonce is known —
 * in the ViewModel that started the flow — and that is the check that matters. See
 * [HaAuthResults].
 */
class HaAuthActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        HaAuthResults.deliver(
            code = data?.getQueryParameter("code").orEmpty(),
            state = data?.getQueryParameter("state").orEmpty(),
        )
        finish()
    }
}
