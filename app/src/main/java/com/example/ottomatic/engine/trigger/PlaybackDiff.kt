package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.service.NowPlaying
import com.example.ottomatic.core.service.PlaybackChange
import com.example.ottomatic.core.service.PlaybackKind

/**
 * Turns a stream of "here is every player right now" snapshots into the events
 * `trigger.media_playback` reports.
 *
 * Pure Kotlin in `engine/`, like `ProximityDetector` and `OrientationDetector` and for their
 * reason: the classification is the interesting half, it is entirely testable without a
 * device, and leaving it inside the Android watcher would make it reachable only from an
 * instrumentation test nobody runs. `MediaSessionWatchers` holds one of these and does
 * nothing but feed it.
 *
 * **It is fed snapshots rather than deltas because the platform gives snapshots.**
 * `MediaSessionManager` hands over the whole list of active sessions and each controller
 * reports its own current state; nothing anywhere says "this one just paused". So the state
 * transition is derived here, by remembering what each player was doing last time.
 *
 * **This is also the debounce, and it is the only one needed.** A `MediaController.Callback`
 * fires on every playback-state update, which on a playing track is several times a second
 * as the position advances. Position is deliberately not one of the facts compared, so all
 * of those produce no event at all — where a timer-based debounce would have delayed real
 * events to suppress imaginary ones.
 *
 * **Paused and stopped are told apart by whether the session is still there**, which is the
 * one rule here that is a judgement rather than a reading. A player that pauses keeps its
 * session and its notification — that is what makes the notification's play button work —
 * and a player that is closed, or that finishes a queue and gives up audio focus, drops off
 * the list entirely. A `PlaybackState` of `STATE_STOPPED` exists and is not used, because
 * players disagree about it wildly: several report it while still showing a paused
 * notification, and several never report it at all.
 */
class PlaybackDiff {

    private val seen = mutableMapOf<String, NowPlaying>()

    /**
     * The events [snapshot] implies, given everything seen before it.
     *
     * Empty for the overwhelmingly common case that nothing derived has changed. Keyed by
     * package rather than by session token: a player that recreates its session mid-track —
     * which several do when moving between local and cast playback — is the same player to
     * everyone looking at the phone, and keying by token would report that as a stop and a
     * fresh start.
     */
    fun update(snapshot: List<NowPlaying>): List<PlaybackChange> {
        val current = snapshot.associateBy { it.app }
        val events = buildList {
            current.forEach { (app, now) -> changeFor(seen[app], now)?.let(::add) }
            // A player that vanished while playing has stopped. One that vanished while
            // already paused has nothing left to report: the pause was reported when it
            // happened, and a second event for the notification finally going away would
            // fire a macro twice for one thing the user did once.
            seen.forEach { (app, before) ->
                if (app !in current && before.playing) {
                    add(PlaybackChange(PlaybackKind.STOPPED, before.copy(playing = false)))
                }
            }
        }
        val remembered = current.mapValues { (app, now) -> remember(seen[app], now) }
        seen.clear()
        seen.putAll(remembered)
        return events
    }

    /**
     * What to remember this player as, which is **not always the reading just taken**.
     *
     * A player that clears its metadata before republishing passes through a moment with no
     * title at all. [sameTrack] rightly treats that blank as "not said yet" rather than as a
     * change — but storing it would then make the *next* reading a blank-to-named comparison
     * too, and the real track change would be swallowed instead of reported twice. So a
     * blank identity keeps whatever was last named, and only the description is held back:
     * everything else about the reading, [NowPlaying.playing] above all, is current.
     */
    private fun remember(before: NowPlaying?, now: NowPlaying): NowPlaying =
        if (before != null && identity(now).isBlank() && identity(before).isNotBlank()) {
            now.copy(title = before.title, artist = before.artist, album = before.album)
        } else {
            now
        }

    /**
     * Forgets everything, so the next snapshot is a baseline rather than a diff.
     *
     * Called when the platform listener is dropped. Without it, re-arming after a spell with
     * nothing armed would compare against a state from before the gap and announce a stop
     * for every player that had moved on in the meantime — events about a period nobody was
     * listening to.
     */
    fun reset() = seen.clear()

    /**
     * The one event [now] implies for a single player, or null.
     *
     * At most one per player per snapshot, and the order of the branches is the priority:
     * starting to play wins over the track having changed, because a player that resumes on
     * a different track is one thing that happened and "started" is the fact a macro acts
     * on. A track change is only reported while playback *continues*, which is what makes
     * it mean "the album moved on" rather than "something else happened as well".
     */
    private fun changeFor(before: NowPlaying?, now: NowPlaying): PlaybackChange? = when {
        now.playing && before?.playing != true -> PlaybackChange(PlaybackKind.STARTED, now)
        !now.playing && before?.playing == true -> PlaybackChange(PlaybackKind.PAUSED, now)
        now.playing && before != null && !sameTrack(before, now) ->
            PlaybackChange(PlaybackKind.TRACK_CHANGED, now)
        else -> null
    }

    /**
     * Whether two readings are of the same track.
     *
     * **Compared on [identity] rather than field by field, and that is the fix for a real
     * double-fire.** Players publish metadata in *stages*: the title lands, then the artist,
     * then the album, then the artwork, each as its own `onMetadataChanged`. Comparing the
     * three fields separately made every one of those stages a track change, so one song
     * ending reported two or three.
     *
     * Position and duration were already excluded — the first moves constantly and the
     * second is republished as metadata fills in — and this is the same argument carried to
     * its conclusion: the only field that *names* the track is the title, and everything
     * else is description that happens to arrive with it.
     *
     * **A blank on either side is metadata that has not arrived yet, not a different
     * track.** That is what keeps the first stage of a change from reporting twice: blank →
     * "Temptation" is the same track being described, and "Blue Monday" → "Temptation" is
     * the change.
     *
     * The price is a player that reuses one title for everything it plays — an internet
     * radio stream naming the station rather than the song. That case loses the event
     * rather than gaining a spurious one, which is the right way round, and [identity] still
     * falls back to artist and album for a player that publishes no title at all.
     */
    private fun sameTrack(a: NowPlaying, b: NowPlaying): Boolean {
        val before = identity(a)
        val now = identity(b)
        return before.isBlank() || now.isBlank() || before == now
    }

    /**
     * What names this track, as far as the player has said.
     *
     * The title, or what description there is when there is no title. Artist and album are
     * the fallback rather than part of the identity because they arrive on their own
     * schedule — and because one of them can legitimately *change* for the same track, when
     * a player publishes the album artist first and the track artist a moment later.
     */
    private fun identity(track: NowPlaying): String = track.title.ifBlank {
        sequenceOf(track.artist, track.album).filter(String::isNotBlank).joinToString(" - ")
    }
}
