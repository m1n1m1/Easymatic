package com.example.ottomatic.data.homeassistant

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** An authorization code coming back from the browser. */
data class HaAuthCode(val code: String, val state: String)

/**
 * Where the browser's redirect lands, on its way from an Activity to a ViewModel.
 *
 * A process-wide `object` on `ActiveNotifications`' and `VariableStore`'s reasoning, and
 * for exactly their reason: **an Activity is constructed by the system and can never be
 * handed a dependency.** `HaAuthActivity` exists only to receive an intent, so there is
 * nowhere to inject anything into it and nothing to inject.
 *
 * The **nonce check happens at the far end**, in the ViewModel that started the flow,
 * not here. That is deliberate: a custom URI scheme is not exclusive on Android and any
 * app can fire an intent at `ottomatic://ha-auth`, so what arrives here is untrusted by
 * construction. Only the code that generated a nonce knows which one it is waiting for,
 * and comparing there is what stops another app's redirect completing somebody else's
 * sign-in.
 *
 * `replay = 1` because the two ends are not synchronised: Chrome resumes this app and
 * the Activity fires immediately, which can be before the ViewModel is collecting again
 * after the process was backgrounded. Without a replay the code would arrive at nobody
 * and the flow would hang on a screen with no error to show.
 */
object HaAuthResults {

    private val _codes = MutableSharedFlow<HaAuthCode>(replay = 1, extraBufferCapacity = 1)

    val codes: SharedFlow<HaAuthCode> = _codes.asSharedFlow()

    /** Called from the redirect Activity, on the main thread, and never blocking. */
    fun deliver(code: String, state: String) {
        if (code.isBlank() || state.isBlank()) return
        _codes.tryEmit(HaAuthCode(code, state))
    }

    /**
     * Drops anything replayed, once it has been dealt with.
     *
     * Needed because of the replay: a code left in the buffer would be re-delivered the
     * next time somebody opened the setup screen, and re-presented to the server, where
     * an authorization code is single-use and the second attempt fails — reporting a
     * sign-in failure for a sign-in nobody started.
     */
    fun clear() {
        _codes.resetReplayCache()
    }
}
