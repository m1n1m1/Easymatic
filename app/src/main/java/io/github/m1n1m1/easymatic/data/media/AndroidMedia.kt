package io.github.m1n1m1.easymatic.data.media

import android.content.Context
import android.media.session.MediaController
import io.github.m1n1m1.easymatic.core.service.Media
import io.github.m1n1m1.easymatic.core.service.MediaCommand
import io.github.m1n1m1.easymatic.core.service.MediaOutcome
import io.github.m1n1m1.easymatic.core.service.NowPlaying
import io.github.m1n1m1.easymatic.core.service.SeekMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android implementation of [Media], over `MediaSessionManager`.
 *
 * Every member is wrapped in `runCatching`, on `AndroidSystemServices`' discipline, and here
 * it is load-bearing rather than defensive: reading sessions raises `SecurityException`
 * whenever notification access is off, which is where every install starts.
 *
 * **Nothing is cached.** The sessions are asked for on every call, on `AndroidCalendars`'
 * reasoning and for the case that happens most: somebody grants notification access on the
 * Permissions screen and runs the macro from the next screen along. A snapshot taken at
 * construction would leave that phone reading null until the process restarted. The read is
 * one binder call, which is what puts it on the pull side in the first place.
 *
 * The two reads are **not** suspending, matching `DeviceState`'s synchronous shape — they
 * are what `value.now_playing` and `value.media_playing` call, and the executor already
 * pulls values off the main thread. The two writes are, so the binder round trip lands on
 * IO wherever the executor happens to be.
 */
class AndroidMedia(context: Context) : Media {

    private val appContext = context.applicationContext

    override fun nowPlaying(app: String): NowPlaying? {
        val controller = MediaSessions.choose(MediaSessions.active(appContext), app) ?: return null
        val packageName = controller.packageName.orEmpty()
        return MediaSessions.reading(controller, MediaSessions.appLabel(appContext, packageName))
    }

    /**
     * Whether anything is playing.
     *
     * **Null when the session list could not be read at all**, which is the distinction
     * `value.media_playing` is built on: an empty list means nothing is playing, and a
     * failure means we do not know. `MediaSessions.active` collapses both into an empty
     * list, so the check has to be made here rather than through it.
     */
    override fun isPlaying(): Boolean? =
        MediaSessions.activeOrNull(appContext)?.any { MediaSessions.isPlaying(it) }

    override suspend fun control(command: MediaCommand, app: String): MediaOutcome =
        withContext(Dispatchers.IO) {
            onController(app) { controller ->
                val transport = controller.transportControls
                when (command) {
                    MediaCommand.PLAY -> transport.play()
                    MediaCommand.PAUSE -> transport.pause()
                    // Read the state and send the opposite, rather than dispatching a
                    // KEYCODE_MEDIA_PLAY_PAUSE key event: a session may handle the two
                    // explicit commands and not the key, and the state is right here.
                    MediaCommand.PLAY_PAUSE ->
                        if (MediaSessions.isPlaying(controller)) transport.pause() else transport.play()
                    MediaCommand.NEXT -> transport.skipToNext()
                    MediaCommand.PREVIOUS -> transport.skipToPrevious()
                    MediaCommand.STOP -> transport.stop()
                }
                MediaOutcome(changed = true, app = controller.packageName.orEmpty())
            }
        }

    /**
     * Moves the position, clamping to the track.
     *
     * A relative move needs the player's current position, and a player that publishes none
     * is reported rather than seeked from zero — jumping to the start of a podcast because
     * somebody asked to skip thirty seconds is a worse answer than saying it could not be
     * done. [SeekMode.TO] needs no position and therefore still works there.
     *
     * The clamp is deliberate on both ends. "Back thirty seconds" ten seconds in means the
     * beginning, which is what anybody pressing it expects; failing there would break the
     * node at the moment it is most used. The far end is clamped only when the player says
     * how long the track is, since -1 means it did not.
     */
    override suspend fun seek(mode: SeekMode, seconds: Int, app: String): MediaOutcome =
        withContext(Dispatchers.IO) {
            onController(app) { controller ->
                val target = targetPosition(controller, mode, seconds)
                    ?: return@onController MediaOutcome(
                        app = controller.packageName.orEmpty(),
                        error = "This player does not say where it is, so it cannot be moved by a number of seconds",
                    )
                controller.transportControls.seekTo(target)
                MediaOutcome(changed = true, app = controller.packageName.orEmpty(), positionMs = target)
            }
        }

    private fun targetPosition(controller: MediaController, mode: SeekMode, seconds: Int): Long? {
        val delta = seconds.toLong() * MILLIS_PER_SECOND
        val duration = MediaSessions.reading(controller, "").durationMs
        val raw = when (mode) {
            SeekMode.TO -> delta
            SeekMode.FORWARD, SeekMode.BACK -> {
                val current = controller.playbackState?.position?.takeIf { it >= 0 } ?: return null
                if (mode == SeekMode.FORWARD) current + delta else current - delta
            }
        }
        return raw.coerceAtLeast(0).let { if (duration > 0) it.coerceAtMost(duration) else it }
    }

    /**
     * Runs [block] against the chosen player, or reports that there is none.
     *
     * The two "nothing happened" answers are kept apart on purpose. No session at all is
     * `changed = false` with **no error** — asking a quiet phone to pause is a perfectly
     * reasonable thing for a macro to do, and the node logs it as a fact rather than a
     * problem. A *named* app that is not running is an error, because the macro said which
     * player it meant and did not reach it.
     */
    @Suppress("ReturnCount") // One exit per way of not reaching a player; a combined
    // expression would have to compute all three and would read as one condition.
    private inline fun onController(app: String, block: (MediaController) -> MediaOutcome): MediaOutcome {
        val controllers = MediaSessions.activeOrNull(appContext)
            ?: return MediaOutcome(app = app, error = NO_ACCESS)
        val controller = MediaSessions.choose(controllers, app)
            ?: return if (app.isBlank()) {
                MediaOutcome(changed = false)
            } else {
                MediaOutcome(app = app, error = "$app is not playing anything, so it was not reached")
            }
        return runCatching { block(controller) }.getOrElse {
            MediaOutcome(
                app = controller.packageName.orEmpty(),
                error = "The player did not accept that: ${it.message.orEmpty().ifBlank { "no reason given" }}",
            )
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L

        // The only failure `MediaSessions.active` hides, and the one worth naming: without
        // notification access the platform refuses to list sessions at all, and every node
        // in the family is dead until it is granted.
        const val NO_ACCESS =
            "Easymatic does not have notification access, so it cannot see or control any media player"
    }
}
