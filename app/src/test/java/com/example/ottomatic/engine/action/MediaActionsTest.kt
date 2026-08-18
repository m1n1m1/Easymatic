package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.MediaCommand
import com.example.ottomatic.core.service.MediaOutcome
import com.example.ottomatic.core.service.SeekMode
import com.example.ottomatic.engine.DefaultExecutionContext
import com.example.ottomatic.engine.RecordingMedia
import com.example.ottomatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two media actions over the shapes their config actually holds.
 *
 * The load-bearing assertions are that the **chosen command and the chosen app reach the
 * facade unchanged** — the node's whole job — and that neither node halts a run when there
 * is nothing to control. A media command failing is the ordinary case, not the exceptional
 * one: a phone with nothing playing is most phones most of the time.
 */
class MediaActionsTest {

    private val media = RecordingMedia()
    private val logs = mutableListOf<String>()
    private val context = DefaultExecutionContext(
        systemServices = RecordingSystemServices(),
        media = media,
    ) { entry -> logs += entry.message }

    private val control = MediaControlAction()
    private val seek = MediaSeekAction()

    @Test
    fun `every command choice maps to its facade command`() = runBlocking {
        MediaCommandChoice.entries.forEach { choice ->
            control.execute(MediaControlConfig(command = choice), context)
        }
        assertEquals(
            listOf(
                MediaCommand.PLAY,
                MediaCommand.PAUSE,
                MediaCommand.PLAY_PAUSE,
                MediaCommand.NEXT,
                MediaCommand.PREVIOUS,
                MediaCommand.STOP,
            ),
            media.commands.map { it.first },
        )
    }

    @Test
    fun `a chosen app reaches the facade, trimmed`() = runBlocking {
        control.execute(MediaControlConfig(app = "  com.spotify.music  "), context)
        assertEquals("com.spotify.music", media.commands.single().second)
    }

    /** Blank is a real answer — it means "whatever is playing" — and must stay blank. */
    @Test
    fun `a blank app is passed through rather than substituted`() = runBlocking {
        control.execute(MediaControlConfig(), context)
        assertEquals("", media.commands.single().second)
    }

    @Test
    fun `every seek mode maps to its facade mode`() = runBlocking {
        SeekModeChoice.entries.forEach { choice ->
            seek.execute(MediaSeekConfig(mode = choice, seconds = 30), context)
        }
        assertEquals(
            listOf(SeekMode.FORWARD, SeekMode.BACK, SeekMode.TO),
            media.seeks.map { it.first },
        )
        assertTrue(media.seeks.all { it.second == 30 })
    }

    /**
     * Nothing playing is reported and does not halt the run.
     *
     * The receipt carries `changed = false` and **no error**, because asking a quiet phone to
     * pause is a perfectly reasonable thing for a macro to do — and the line still gets
     * written, since a bare false with nothing said is what sends somebody looking for a bug
     * in their headphones.
     */
    @Test
    fun `nothing playing is a line in the console rather than a failure`() = runBlocking {
        media.outcome = MediaOutcome(changed = false)
        val output = control.execute(MediaControlConfig(command = MediaCommandChoice.PAUSE), context)
        assertFalse(output.value.changed)
        assertEquals("", output.value.error)
        assertFalse(output.halt)
        assertTrue(logs.single().contains("Nothing is playing"))
    }

    @Test
    fun `a refused command lands on the receipt rather than throwing`() = runBlocking {
        media.outcome = MediaOutcome(error = "no access")
        val output = seek.execute(MediaSeekConfig(), context)
        assertEquals("no access", output.value.error)
        assertFalse(output.halt)
        assertEquals(listOf("no access"), logs)
    }

    /**
     * The command a seek reports is the mode, not the word "seek" alone.
     *
     * It is what the console line and any downstream comparison read, so a macro logging
     * what it did can tell a forward jump from a rewind.
     */
    @Test
    fun `a seek receipt names which way it moved`() = runBlocking {
        val output = seek.execute(MediaSeekConfig(mode = SeekModeChoice.BACK), context)
        assertEquals("seek back", output.value.command)
    }
}
