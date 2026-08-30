package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.core.service.NowPlaying
import io.github.m1n1m1.easymatic.core.service.PlaybackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The started/paused/stopped/track-changed table, and the silence between the events.
 *
 * The load-bearing case is the **empty diff**: `MediaController.Callback` fires several
 * times a second while a track plays, and every one of those has to produce nothing. That is
 * the only debounce `trigger.media_playback` has, so a regression here would not look like a
 * wrong event — it would look like a macro running four times a second.
 *
 * The second is that **paused and stopped are told apart by whether the session survives**.
 * A `PlaybackState` of `STATE_STOPPED` exists and is deliberately not consulted: players
 * disagree about it wildly, several reporting it while still showing a paused notification.
 */
class PlaybackDiffTest {

    private val diff = PlaybackDiff()

    private fun track(
        app: String = "com.spotify.music",
        title: String = "Blue Monday",
        artist: String = "New Order",
        playing: Boolean = true,
        positionMs: Long = 0,
    ) = NowPlaying(app = app, title = title, artist = artist, playing = playing, positionMs = positionMs)

    @Test
    fun `a player that starts playing reports started`() {
        val events = diff.update(listOf(track()))
        assertEquals(listOf(PlaybackKind.STARTED), events.map { it.kind })
        assertEquals("Blue Monday", events.single().track.title)
    }

    /** The one that matters most: a playing track that keeps playing says nothing. */
    @Test
    fun `an unchanged snapshot reports nothing`() {
        diff.update(listOf(track()))
        assertTrue(diff.update(listOf(track())).isEmpty())
    }

    /**
     * Position is not a fact the diff compares, which is what makes the callback storm free.
     *
     * A player republishes its state every second or so with nothing but the position moved,
     * and comparing it would turn each of those into a track change.
     */
    @Test
    fun `a moving position is not a change`() {
        diff.update(listOf(track(positionMs = 1_000)))
        assertTrue(diff.update(listOf(track(positionMs = 2_000))).isEmpty())
        assertTrue(diff.update(listOf(track(positionMs = 3_000))).isEmpty())
    }

    @Test
    fun `a session that stays but stops playing reports paused`() {
        diff.update(listOf(track()))
        assertEquals(
            listOf(PlaybackKind.PAUSED),
            diff.update(listOf(track(playing = false))).map { it.kind },
        )
    }

    @Test
    fun `a session that disappears while playing reports stopped`() {
        diff.update(listOf(track()))
        val events = diff.update(emptyList())
        assertEquals(listOf(PlaybackKind.STOPPED), events.map { it.kind })
        // The last reading is carried through, so the macro can still say what stopped —
        // and `playing` is corrected to false, since it plainly is not any more.
        assertEquals("Blue Monday", events.single().track.title)
        assertEquals(false, events.single().track.playing)
    }

    /**
     * A paused session going away is not a second event.
     *
     * The pause was reported when it happened; firing again when the notification is finally
     * swiped away would run the macro twice for one thing the user did once.
     */
    @Test
    fun `a session that disappears while already paused reports nothing`() {
        diff.update(listOf(track()))
        diff.update(listOf(track(playing = false)))
        assertTrue(diff.update(emptyList()).isEmpty())
    }

    @Test
    fun `a new track while playback continues reports track changed`() {
        diff.update(listOf(track(title = "Blue Monday")))
        val events = diff.update(listOf(track(title = "Temptation")))
        assertEquals(listOf(PlaybackKind.TRACK_CHANGED), events.map { it.kind })
        assertEquals("Temptation", events.single().track.title)
    }

    /**
     * Resuming on a different track is one event, and it is `STARTED`.
     *
     * Both facts changed, but only one thing happened, and "started" is the one a macro acts
     * on — a rule the branch order in `changeFor` encodes.
     */
    @Test
    fun `resuming on a different track reports started and not also a track change`() {
        diff.update(listOf(track(title = "Blue Monday", playing = false)))
        val events = diff.update(listOf(track(title = "Temptation")))
        assertEquals(listOf(PlaybackKind.STARTED), events.map { it.kind })
    }

    /** A player that appears already paused was never playing, so nothing happened. */
    @Test
    fun `a session that appears paused reports nothing`() {
        assertTrue(diff.update(listOf(track(playing = false))).isEmpty())
    }

    @Test
    fun `two players are diffed independently`() {
        diff.update(listOf(track(app = "a"), track(app = "b", playing = false)))
        val events = diff.update(listOf(track(app = "a", title = "Temptation"), track(app = "b")))
        assertEquals(
            mapOf("a" to PlaybackKind.TRACK_CHANGED, "b" to PlaybackKind.STARTED),
            events.associate { it.track.app to it.kind },
        )
    }

    /**
     * Keyed by package, not by session token.
     *
     * A player that recreates its session mid-track — several do when moving between local
     * and cast playback — is the same player to everyone looking at the phone, so this must
     * not read as a stop followed by a fresh start.
     */
    @Test
    fun `a player is the same player across snapshots`() {
        diff.update(listOf(track()))
        assertTrue(diff.update(listOf(track())).isEmpty())
    }

    /**
     * Resetting takes a fresh baseline rather than announcing the gap.
     *
     * Without it, re-arming after a spell with nothing armed would report a stop for every
     * player that had moved on while nobody was listening.
     */
    @Test
    fun `reset makes the next snapshot a baseline`() {
        diff.update(listOf(track()))
        diff.reset()
        // Nothing is playing now, and the player that was is not announced as stopped.
        assertTrue(diff.update(emptyList()).isEmpty())
    }

    /**
     * A player publishing no metadata reports no track changes.
     *
     * Blank compares equal to blank, which is right: it never said the track changed, and
     * inventing the event on every callback would be worse than not having it.
     */
    @Test
    fun `a player with no metadata never reports a track change`() {
        val bare = NowPlaying(app = "com.example.radio", playing = true)
        assertEquals(listOf(PlaybackKind.STARTED), diff.update(listOf(bare)).map { it.kind })
        assertTrue(diff.update(listOf(bare)).isEmpty())
    }

    /**
     * Staged metadata is one track change, not three.
     *
     * The bug this pins: players publish metadata in instalments — the title lands, then the
     * artist, then the album — each as its own `onMetadataChanged` and so its own snapshot.
     * Comparing title, artist and album separately made every instalment a track change, so
     * one song ending fired the macro three times.
     */
    @Test
    fun `metadata arriving in stages is one track change`() {
        diff.update(listOf(track(title = "Blue Monday", artist = "New Order")))

        val onTitle = diff.update(listOf(track(title = "Temptation", artist = "")))
        val onArtist = diff.update(listOf(track(title = "Temptation", artist = "New Order")))
        val onAlbum = diff.update(
            listOf(track(title = "Temptation", artist = "New Order").copy(album = "Substance")),
        )

        assertEquals(listOf(PlaybackKind.TRACK_CHANGED), onTitle.map { it.kind })
        assertTrue("the artist arriving is not a second track change", onArtist.isEmpty())
        assertTrue("the album arriving is not a third track change", onAlbum.isEmpty())
    }

    /**
     * The first instalment of a change is not itself two events.
     *
     * A player that clears its metadata before republishing goes through a blank title, and
     * a blank has to read as "not said yet" rather than as a track called nothing.
     */
    @Test
    fun `a blank title between two tracks is not a change of its own`() {
        diff.update(listOf(track(title = "Blue Monday")))

        val cleared = diff.update(listOf(track(title = "", artist = "")))
        val republished = diff.update(listOf(track(title = "Temptation", artist = "New Order")))

        assertTrue("clearing the metadata is not a track change", cleared.isEmpty())
        assertEquals(listOf(PlaybackKind.TRACK_CHANGED), republished.map { it.kind })
    }

    /**
     * The artist changing under a stable title is not a track change.
     *
     * A player may publish the album artist first and the track artist a moment later, which
     * is one track described twice — and on a compilation the two genuinely differ.
     */
    @Test
    fun `an artist correction under the same title is not a change`() {
        diff.update(listOf(track(title = "Blue Monday", artist = "Various Artists")))
        assertTrue(diff.update(listOf(track(title = "Blue Monday", artist = "New Order"))).isEmpty())
    }

    /** With no title at all, artist and album are what names the track. */
    @Test
    fun `a player with no title is identified by artist and album`() {
        val first = NowPlaying(app = "a", title = "", artist = "New Order", playing = true)
        diff.update(listOf(first))
        val events = diff.update(listOf(first.copy(artist = "Joy Division")))
        assertEquals(listOf(PlaybackKind.TRACK_CHANGED), events.map { it.kind })
    }
}
