package io.github.m1n1m1.easymatic.data.speech

import android.content.Context
import android.media.AudioManager

/**
 * Silences the tones the recogniser plays when it starts and stops listening.
 *
 * **There is no API for this, and that is not an oversight in the search — it is a stated
 * limitation of `SpeechRecognizer`.** The start and end tones are played by the recognition
 * service itself, and Google has carried an open request to make them optional since
 * Android 4.1. Muting the streams they play on is the only thing an app can do, and it is
 * what every continuous-dictation app on the platform does.
 *
 * **It only silences *restarts*, never the first listen**, and that split is the whole
 * design. One tone at the start is not a defect: it is the phone saying "speak now", which
 * `action.listen` has always relied on and nobody has ever complained about. What is
 * intolerable is hearing it again every time a pause ends an utterance — which is the price
 * of holding a session open by restarting, and is a price the user should not pay.
 *
 * ### Why it puts the streams back exactly as it found them
 *
 * Muting is a change to something the user owns. A stream they had *already* muted must
 * stay muted afterwards, so this records what it actually changed and restores only that —
 * blanket unmuting at the end would be this feature quietly turning somebody's
 * notifications back on.
 *
 * ### What it does about being refused
 *
 * From Android 7 muting throws when Do Not Disturb is on and the app has no notification
 * policy access. That is caught and ignored: the failure is that the tones are audible,
 * which is the state this class exists to improve on rather than a reason to fail a
 * transcription. Nothing here throws.
 */
internal class RecognitionBeeps(context: Context) {

    private val appContext = context.applicationContext

    /** The streams this instance muted, so restoring changes nothing else. */
    private val silenced = mutableListOf<Int>()

    private val audio: AudioManager?
        get() = runCatching {
            appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }.getOrNull()

    /** Mutes whichever beep streams are not already muted. Safe to call more than once. */
    fun silence() {
        val manager = audio ?: return
        // Filtered rather than `continue`d, so the loop states its one job. A stream already
        // muted is left alone on purpose — see the class KDoc on restoring.
        val toMute = BEEP_STREAMS
            .filterNot { it in silenced }
            .filterNot { runCatching { manager.isStreamMute(it) }.getOrDefault(true) }
        for (stream in toMute) {
            val muted = runCatching {
                // No FLAG_SHOW_UI: `action.volume` shows the slider because a macro changing
                // the volume is something the user asked for and should see. This is
                // housekeeping around a restart, and a volume panel flashing up twice a
                // sentence would be worse than the tone it removes.
                manager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
            }.isSuccess
            if (muted) silenced += stream
        }
    }

    /** Puts back exactly the streams [silence] muted, and nothing else. */
    fun restore() {
        val manager = audio
        if (manager != null) {
            for (stream in silenced) {
                runCatching { manager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0) }
            }
        }
        silenced.clear()
    }

    private companion object {
        /**
         * Where the tones play, which is not the same on every phone.
         *
         * Google's recogniser uses the media stream, and OEM implementations have been seen
         * on the system and notification ones. All three are muted because the cost of
         * muting one that was not going to make a sound is nothing, and the cost of missing
         * the one that was is the entire feature.
         */
        val BEEP_STREAMS = listOf(
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_NOTIFICATION,
        )
    }
}
