package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.LogEntry
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.RecordingOutcome
import com.example.ottomatic.core.service.RecordingQuality
import com.example.ottomatic.core.service.WhenExists
import com.example.ottomatic.domain.model.config.NoConfig
import com.example.ottomatic.engine.DefaultExecutionContext
import com.example.ottomatic.engine.RecordingMicrophone
import com.example.ottomatic.engine.RecordingSystemServices
import com.example.ottomatic.engine.value.RecordingValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three recording actions and `value.recording`, over the outcomes that must not be
 * collapsed.
 *
 * The load-bearing ones are the failures: **a recording that could not happen still pulses
 * `out`**. Every reason this family fails — the grant refused, another app holding the
 * microphone, a recording already running, a stop with nothing to stop — is a fact about the
 * phone or about the order the graph ran in, never a reason to strand every node downstream
 * with nothing said. The reason has to reach `error` so a macro can branch on it.
 */
class RecordAudioActionTest {

    private val logs = mutableListOf<LogEntry>()
    private val microphone = RecordingMicrophone()
    private val context = DefaultExecutionContext(
        systemServices = RecordingSystemServices(),
        microphone = microphone,
        logger = { logs += it },
    )

    private fun levels() = logs.map { it.level }

    @Test
    fun `a recording reports what it saved and logs at INFO`() = runBlocking {
        microphone.outcome = RecordingOutcome(
            changed = true,
            path = "Recordings/note.m4a",
            name = "note.m4a",
            durationMs = 5_012,
            sizeBytes = 41_233,
        )

        val out = RecordAudioAction().execute(RecordAudioConfig(seconds = 5), context)

        assertTrue(out.value.changed)
        assertEquals("note.m4a", out.value.name)
        assertEquals(5_012, out.value.durationMs)
        assertEquals(41_233, out.value.sizeBytes)
        assertEquals(listOf(LogLevel.INFO), levels())
    }

    @Test
    fun `the config reaches the facade whole`() = runBlocking {
        RecordAudioAction().execute(
            RecordAudioConfig(
                seconds = 30,
                quality = RecordingQualityChoice.HIGH,
                toFolder = "  /storage/emulated/0/Music  ",
                name = "  band  ",
                whenExists = WriteCollision.SKIP,
            ),
            context,
        )

        val request = microphone.recorded.single()
        assertEquals(30, request.seconds)
        assertEquals(RecordingQuality.HIGH, request.quality)
        assertEquals("/storage/emulated/0/Music", request.toFolder)
        assertEquals("band", request.name)
        assertEquals(WhenExists.SKIP, request.whenExists)
    }

    /**
     * A length of zero never reaches the microphone.
     *
     * Refused here rather than in `data/` because the honest answer is about the *config* —
     * "you asked for nothing" — and a facade that opened a recorder only to close it again
     * would report a hardware problem for a typing mistake.
     */
    @Test
    fun `a length of zero is refused without opening anything`() = runBlocking {
        val out = RecordAudioAction().execute(RecordAudioConfig(seconds = 0), context)

        assertFalse(out.value.changed)
        assertTrue(out.value.error.isNotBlank())
        assertTrue("nothing should have been opened", microphone.recorded.isEmpty())
        assertEquals(listOf(LogLevel.WARN), levels())
    }

    @Test
    fun `a failed recording carries the reason and still pulses out`() = runBlocking {
        microphone.outcome = RecordingOutcome(error = "The microphone is not available")

        val out = RecordAudioAction().execute(RecordAudioConfig(seconds = 5), context)

        assertFalse(out.value.changed)
        assertEquals("The microphone is not available", out.value.error)
        assertEquals(listOf(LogLevel.WARN), levels())
    }

    /**
     * `action.record_start` reports the problem and carries on.
     *
     * A second start must not take the microphone from the first, and the macro that
     * reached it is usually one that simply ran twice — so this is a console line, not a
     * halt.
     */
    @Test
    fun `a start that is refused logs a warning and still pulses out`() = runBlocking {
        microphone.startProblem = "A recording is already running"

        RecordStartAction().execute(RecordStartConfig(maxSeconds = 60), context)

        assertEquals(listOf(LogLevel.WARN), levels())
        assertEquals(1, microphone.started.size)
    }

    @Test
    fun `a start passes its limit through as the recording's seconds`() = runBlocking {
        RecordStartAction().execute(RecordStartConfig(maxSeconds = 120), context)

        assertEquals(120, microphone.started.single().seconds)
        assertEquals(listOf(LogLevel.INFO), levels())
    }

    /** A limit of zero would be a recording nothing ever stops. */
    @Test
    fun `a limit of zero is refused without opening anything`() = runBlocking {
        RecordStartAction().execute(RecordStartConfig(maxSeconds = 0), context)

        assertTrue("nothing should have been opened", microphone.started.isEmpty())
        assertEquals(listOf(LogLevel.WARN), levels())
    }

    @Test
    fun `stopping nothing reports it rather than passing silently`() = runBlocking {
        microphone.outcome = RecordingOutcome(error = "Nothing is recording")

        val out = RecordStopAction().execute(NoConfig, context)

        assertFalse(out.value.changed)
        assertEquals("Nothing is recording", out.value.error)
        assertEquals(1, microphone.stops)
        assertEquals(listOf(LogLevel.WARN), levels())
    }

    @Test
    fun `the value answers the flag either way and never null`() = runBlocking {
        val node = RecordingValue()

        microphone.running = true
        assertEquals(true, node.read(NoConfig, context))

        microphone.running = false
        assertEquals(false, node.read(NoConfig, context))
    }
}
