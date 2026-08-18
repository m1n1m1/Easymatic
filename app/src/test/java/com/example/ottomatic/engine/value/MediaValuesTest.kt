package com.example.ottomatic.engine.value

import com.example.ottomatic.core.service.NowPlaying
import com.example.ottomatic.domain.model.config.NoConfig
import com.example.ottomatic.engine.DefaultExecutionContext
import com.example.ottomatic.engine.RecordingMedia
import com.example.ottomatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two media values, over the distinction they exist to keep.
 *
 * The load-bearing assertion is that a **refused grant reads null rather than false**. That
 * is not a stylistic preference: a false would make a revoked permission indistinguishable
 * from silence, so "if nothing is playing, start the podcast" would talk over music already
 * running. Null contributes no item and the comparison downstream fails closed.
 */
class MediaValuesTest {

    private val media = RecordingMedia()
    private val context = DefaultExecutionContext(
        systemServices = RecordingSystemServices(),
        media = media,
    )

    private val playing = MediaPlayingValue()
    private val nowPlaying = NowPlayingValue()

    @Test
    fun `a refused grant reads null, not false`() = runBlocking {
        media.playing = null
        assertNull(playing.read(NoConfig, context))
    }

    @Test
    fun `nothing playing reads false`() = runBlocking {
        media.playing = false
        assertEquals(false, playing.read(NoConfig, context))
    }

    @Test
    fun `something playing reads true`() = runBlocking {
        media.playing = true
        assertEquals(true, playing.read(NoConfig, context))
    }

    @Test
    fun `the whole reading crosses into the graph's struct`() = runBlocking {
        media.track = NowPlaying(
            app = "com.spotify.music",
            appName = "Spotify",
            title = "Blue Monday",
            artist = "New Order",
            album = "Power, Corruption & Lies",
            playing = true,
            durationMs = 448_000,
            positionMs = 12_000,
        )
        val item = nowPlaying.read(NoConfig, context)!!
        assertEquals("com.spotify.music", item.app)
        assertEquals("Spotify", item.appName)
        assertEquals("Blue Monday", item.title)
        assertEquals("New Order", item.artist)
        assertEquals("Power, Corruption & Lies", item.album)
        assertTrue(item.playing)
        assertEquals(448_000, item.durationMs)
        assertEquals(12_000, item.positionMs)
    }

    /**
     * A paused player still answers, with `playing` false.
     *
     * "What was I listening to?" has an answer for as long as the notification is up, and
     * losing it the instant somebody hits pause would make this node useless to the macro
     * that runs *after* a pause — which is most of them.
     */
    @Test
    fun `a paused track is still reported`() = runBlocking {
        media.track = NowPlaying(app = "com.spotify.music", title = "Blue Monday", playing = false)
        val item = nowPlaying.read(NoConfig, context)!!
        assertEquals("Blue Monday", item.title)
        assertFalse(item.playing)
    }

    @Test
    fun `nothing playing reads null rather than an empty track`() = runBlocking {
        media.track = null
        assertNull(nowPlaying.read(NoConfig, context))
    }
}
