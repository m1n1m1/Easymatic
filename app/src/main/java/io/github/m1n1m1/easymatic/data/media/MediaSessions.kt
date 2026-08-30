package io.github.m1n1m1.easymatic.data.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import io.github.m1n1m1.easymatic.core.service.NowPlaying
import io.github.m1n1m1.easymatic.data.trigger.NotificationListener

/**
 * The one reading of `MediaSessionManager`, shared by [AndroidMedia] and
 * [MediaSessionWatchers].
 *
 * It exists because those two ask the platform the same three questions — which sessions
 * are there, which one does a blank app mean, what is this one playing — and a second copy
 * of any of them is a way for `value.now_playing` and `trigger.media_playback` to disagree
 * about the same phone. That is `OrientationDetector.orientationOf`'s rule, one family
 * along: the trigger and the value share their reading rather than each deriving it.
 *
 * **Every entry point returns null or an empty list rather than throwing.**
 * [MediaSessionManager.getActiveSessions] raises `SecurityException` whenever notification
 * access is off, and that is not an exceptional state — it is the state every install is in
 * until somebody visits a Settings page this app never sees.
 */
internal object MediaSessions {

    /**
     * Every active session, or an empty list when they cannot be read.
     *
     * The component is this app's own [NotificationListener], which is the only supported
     * way for a normal app to reach media sessions at all: `MEDIA_CONTENT_CONTROL` is the
     * permission that sounds right and is signature-level, so no installed app holds it.
     * The listener need only be **enabled**, not bound to us, so nothing here goes through
     * the service instance.
     */
    fun active(context: Context): List<MediaController> = activeOrNull(context).orEmpty()

    /**
     * Every active session, or **null** when they cannot be read.
     *
     * The distinction [active] throws away, kept for the two callers that need it: an empty
     * list means nothing is playing, and null means notification access is off. Collapsing
     * them is what would make `value.media_playing` answer `false` on a phone playing
     * loudly.
     */
    fun activeOrNull(context: Context): List<MediaController>? = runCatching {
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        manager.getActiveSessions(component(context))
    }.getOrNull()

    /** The `ComponentName` [MediaSessionManager] identifies this app by. */
    fun component(context: Context): ComponentName =
        ComponentName(context, NotificationListener::class.java)

    /**
     * Which controller a request naming [app] means, or null when there is none.
     *
     * Blank picks the first session reported as playing and falls back to the first session
     * there is. Both halves earn their place: the first is what "pause the music" means when
     * a podcast app is also alive but paused, and the fallback is what makes `PLAY` reach the
     * last player used when nothing is playing at all — the list is priority-ordered, so its
     * head is the session holding media-button focus.
     *
     * A named app is matched exactly and **never falls back**. Silently pausing Spotify
     * because the macro asked for a player that is not running would be worse than doing
     * nothing, and doing nothing is already reported.
     */
    fun choose(controllers: List<MediaController>, app: String): MediaController? =
        if (app.isBlank()) {
            controllers.firstOrNull { isPlaying(it) } ?: controllers.firstOrNull()
        } else {
            controllers.firstOrNull { it.packageName == app }
        }

    /** Whether [controller] is playing right now. */
    fun isPlaying(controller: MediaController): Boolean = isPlayingState(controller.playbackState)

    /**
     * Whether a playback state counts as playing.
     *
     * **`STATE_BUFFERING` counts, and that is the second half of a double-fire fix.** A great
     * many players dip through buffering at a track boundary — the old track ends, the next
     * one is fetched, playback resumes — and treating that as not-playing made one song
     * change report a pause and then a start. Nobody paused anything: the player is playing,
     * it just has not got the bytes yet, which is exactly what a progress spinner over a
     * pause button means.
     *
     * `STATE_CONNECTING` deliberately does **not** count. It is a cast device being reached,
     * where nothing is coming out of anything yet and the connection may still fail, so
     * "playing" would be a claim rather than a reading.
     */
    fun isPlayingState(state: PlaybackState?): Boolean =
        state?.state == PlaybackState.STATE_PLAYING || state?.state == PlaybackState.STATE_BUFFERING

    /**
     * What [controller] is playing, as the graph's reading of it.
     *
     * [appLabel] is passed in rather than looked up here so the watcher can resolve a label
     * once per session instead of once per callback — a `PackageManager` query per playback
     * state change would run several times a second on a phone that is simply playing.
     *
     * Numbers are **-1 when the player does not publish them, never 0**: zero is an ordinary
     * position for a track to be at and an ordinary duration for a live stream to report, so
     * it cannot also stand for "unknown".
     */
    fun reading(controller: MediaController, appLabel: String): NowPlaying {
        val metadata = controller.metadata
        val state = controller.playbackState
        return NowPlaying(
            app = controller.packageName.orEmpty(),
            appName = appLabel,
            title = metadata.text(MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata.text(MediaMetadata.METADATA_KEY_ARTIST)
                .ifBlank { metadata.text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) },
            album = metadata.text(MediaMetadata.METADATA_KEY_ALBUM),
            playing = isPlayingState(state),
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 } ?: -1,
            positionMs = state?.position?.takeIf { it >= 0 } ?: -1,
        )
    }

    /**
     * The label a person recognises for [packageName], falling back to the package itself.
     *
     * The raw name is a poor label and a truthful one — an app whose label cannot be read is
     * usually one that has just been uninstalled — where a blank would leave a notification
     * saying a track is playing on nothing at all.
     */
    fun appLabel(context: Context, packageName: String): String = runCatching {
        val manager = context.packageManager
        manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: packageName

    private fun MediaMetadata?.text(key: String): String = this?.getString(key).orEmpty()
}
