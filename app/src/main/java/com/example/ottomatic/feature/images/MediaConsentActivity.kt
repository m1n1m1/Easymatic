package com.example.ottomatic.feature.images

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.util.Log
import com.example.ottomatic.data.images.MediaConsents

/**
 * The one-frame Activity that puts Android's "allow this app to change this photo?" dialog
 * on screen for a macro running in the background.
 *
 * **It exists because `startIntentSenderForResult` is an `Activity` method.** The engine is
 * a foreground service; `Context.startIntentSender` has no result callback, so a service
 * calling it could never tell approval from refusal — and those two have to be reported
 * differently, because one is worth retrying and the other is not. `RunTriggerActivity` is
 * the same trampoline for the same structural reason.
 *
 * It draws nothing of its own. With `MANAGE_MEDIA` granted the system draws nothing either
 * and this finishes in a frame; without it, the system dialog appears over whatever the
 * user was looking at.
 *
 * **`noHistory` is deliberately *not* set in the manifest**, which is the one place this
 * differs from `RunTriggerActivity`. That flag finishes an activity as soon as it stops
 * being visible — which here is exactly when the system consent dialog appears, so the
 * result would never arrive.
 *
 * Every exit reports, including the ones that are not answers: [onDestroy] completes as a
 * refusal if nothing else has, so a swipe-away or a rotation-kill resolves the waiting
 * coroutine instead of hanging the run for ever. [MediaConsents.complete] removes the
 * pending entry atomically, so the second call is a no-op rather than a double-resume.
 */
class MediaConsentActivity : Activity() {

    private var requestId: String = ""
    private var reported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent?.getStringExtra(MediaConsents.EXTRA_REQUEST_ID).orEmpty()
        val sender = requestId.takeIf { it.isNotBlank() }?.let { MediaConsents.senderFor(it) }
        if (sender == null) {
            // The request was cancelled, or the process was restarted between the service
            // starting us and this running. There is nothing to ask about and nobody
            // waiting, so leaving quietly is the whole of the correct behaviour.
            finish()
            return
        }
        // Only launch on a fresh start. A recreate already has a dialog in flight, and
        // launching a second sender would stack two dialogs for one decision.
        if (savedInstanceState == null) launch(sender)
    }

    private fun launch(sender: IntentSender) {
        val started = runCatching {
            startIntentSenderForResult(sender, REQUEST_CODE, null, 0, 0, 0)
        }.isSuccess
        if (!started) {
            Log.w("MediaConsent", "Android refused to show the media consent dialog")
            report(approved = false)
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE) return
        report(MediaConsents.approvedFrom(resultCode))
        finish()
    }

    override fun onDestroy() {
        // The backstop, and the reason a swipe-away does not hang a run: a refusal is the
        // safe reading of "this went away without an answer".
        report(approved = false)
        super.onDestroy()
    }

    private fun report(approved: Boolean) {
        if (reported || requestId.isBlank()) return
        reported = true
        MediaConsents.complete(requestId, approved)
    }

    private companion object {
        const val REQUEST_CODE = 4711
    }
}
