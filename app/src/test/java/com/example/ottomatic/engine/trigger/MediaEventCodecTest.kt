package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.service.NowPlaying
import com.example.ottomatic.core.service.PlaybackChange
import com.example.ottomatic.core.service.PlaybackKind
import com.example.ottomatic.domain.model.schema.DateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bus payload for a playback change, both ways.
 *
 * A key spelled two ways here produces a port that is silently always empty — which is why
 * one object owns both halves and why this asserts on the round trip rather than on the map.
 *
 * The second worry is sharper for this node than for any other in the family: the trigger
 * **filters on two of these keys before decoding anything**, so a payload whose `event` or
 * `packageName` is written under a different name than the filter reads would not produce a
 * wrong item — it would produce a trigger that never fires at all.
 */
class MediaEventCodecTest {

    private val change = PlaybackChange(
        kind = PlaybackKind.TRACK_CHANGED,
        track = NowPlaying(
            app = "com.spotify.music",
            appName = "Spotify",
            title = "Blue Monday",
            artist = "New Order",
            album = "Power, Corruption & Lies",
            playing = true,
            durationMs = 448_000,
        ),
    )

    @Test
    fun `a round trip keeps the change`() {
        assertEquals(change, MediaEventCodec.decode(MediaEventCodec.encode(change)))
    }

    /**
     * Position is deliberately not carried.
     *
     * It moves every second, so a snapshot of it inside an event would be stale before
     * anything downstream read it — and a value that is nearly always wrong is worse than a
     * port that is not there.
     */
    @Test
    fun `position does not travel`() {
        val withPosition = change.copy(track = change.track.copy(positionMs = 12_000))
        assertEquals(-1, MediaEventCodec.decode(MediaEventCodec.encode(withPosition)).track.positionMs)
    }

    /** The two keys the trigger filters on, spelled the way the filter reads them. */
    @Test
    fun `the filtered keys are where the trigger looks`() {
        val payload = MediaEventCodec.encode(change)
        assertEquals("track_changed", payload["event"])
        assertEquals("com.spotify.music", payload["packageName"])
    }

    @Test
    fun `an empty payload decodes to unknown rather than zero`() {
        val decoded = MediaEventCodec.decode(emptyMap())

        assertEquals(-1, decoded.track.durationMs)
        assertEquals("", decoded.track.title)
        assertEquals(false, decoded.track.playing)
    }

    /**
     * An unreadable event decodes to stopped, never started.
     *
     * A macro told that nothing is happening is better off than one told the music began.
     */
    @Test
    fun `an unreadable event falls back to stopped`() {
        val payload = MediaEventCodec.encode(change) + ("event" to "wandered off")
        assertEquals(PlaybackKind.STOPPED, MediaEventCodec.decode(payload).kind)
    }

    @Test
    fun `the item carries the moment the bus stamped`() {
        val item = change.toItem(DateTime(1_755_420_900_000))

        assertEquals("track_changed", item.event)
        assertEquals(DateTime(1_755_420_900_000), item.timestamp)
        assertEquals("Blue Monday", item.title)
        assertEquals("Spotify", item.appName)
    }
}
