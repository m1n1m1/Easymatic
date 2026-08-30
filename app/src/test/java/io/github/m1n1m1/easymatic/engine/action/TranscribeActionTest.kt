package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.AiReply
import io.github.m1n1m1.easymatic.core.service.CaptureOutcome
import io.github.m1n1m1.easymatic.core.service.LogEntry
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.engine.DefaultExecutionContext
import io.github.m1n1m1.easymatic.engine.FakeAi
import io.github.m1n1m1.easymatic.engine.RecordingMicrophone
import io.github.m1n1m1.easymatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `action.ai_listen` — the microphone and a model, with no file in between.
 *
 * **What this pins that nothing else can is that hearing nothing is not a failure.** A
 * macro that listens on a schedule finds the room quiet most of the time, and reporting
 * that as an error would fill the console with red overnight for a node that worked
 * perfectly every time. It lands on the fallback with an INFO line instead, which is the
 * line `action.listen` draws with its `nothing` port.
 *
 * The rest is the shape `TranscribeFileActionTest` pins one node over: the clip reaches the
 * facade as audio, and a blank question stays blank.
 */
class TranscribeActionTest {

    private val logs = mutableListOf<LogEntry>()
    private val ai = FakeAi()
    private val microphone = RecordingMicrophone()
    private val context = DefaultExecutionContext(
        systemServices = RecordingSystemServices(),
        ai = ai,
        microphone = microphone,
        logger = { logs += it },
    )
    private val action = TranscribeAction()

    @Test
    fun `what was heard is sent as audio`() = runBlocking {
        ai.reply = AiReply(text = "they said yes")

        val out = action.executeRaw(TranscribeConfig(modelRef = MODEL), transcribeInput(), context)

        assertEquals("they said yes", out.answer())
        val clip = ai.requests.single().audio.single()
        assertEquals("AAAA", clip.base64)
        assertEquals("audio/wav", clip.mediaType)
    }

    @Test
    fun `the configured length and silence reach the microphone`() = runBlocking {
        action.executeRaw(
            TranscribeConfig(modelRef = MODEL, maxSeconds = 8, silenceSeconds = 2),
            transcribeInput(),
            context,
        )

        val asked = microphone.captures.single()
        assertEquals(8, asked.maxSeconds)
        assertEquals(2, asked.silenceSeconds)
    }

    @Test
    fun `an empty question reaches the facade empty`() = runBlocking {
        action.executeRaw(TranscribeConfig(modelRef = MODEL), transcribeInput(), context)

        assertEquals("", ai.requests.single().prompt)
    }

    @Test
    fun `a question that was asked is passed through`() = runBlocking {
        action.executeRaw(TranscribeConfig(modelRef = MODEL, prompt = "was that a yes?"), transcribeInput(), context)

        assertEquals("was that a yes?", ai.requests.single().prompt)
    }

    /** The line this node exists to draw: a quiet room is the node working. */
    @Test
    fun `nothing said lands on the fallback without an error`() = runBlocking {
        microphone.captured = CaptureOutcome(base64 = "AAAA", mediaType = "audio/wav", heard = false)

        val out = action.executeRaw(
            TranscribeConfig(modelRef = MODEL, fallback = "silence"),
            transcribeInput(),
            context,
        )

        assertEquals("silence", out.answer())
        assertEquals(emptyList<Any>(), ai.requests)
        assertFalse(logs.any { it.level == LogLevel.ERROR })
        assertTrue(logs.any { it.level == LogLevel.INFO && it.message.contains("nothing was said") })
    }

    @Test
    fun `a refused microphone lands on the fallback and says why`() = runBlocking {
        microphone.captured = CaptureOutcome(error = "A recording is already running")

        val out = action.executeRaw(TranscribeConfig(modelRef = MODEL, fallback = "busy"), transcribeInput(), context)

        assertEquals("busy", out.answer())
        assertEquals(emptyList<Any>(), ai.requests)
        assertTrue(logs.any { it.level == LogLevel.ERROR && it.message.contains("already running") })
    }

    @Test
    fun `a refused request lands on the fallback and still pulses out`() = runBlocking {
        ai.reply = AiReply(error = "API key not valid")

        val out = action.executeRaw(
            TranscribeConfig(modelRef = MODEL, fallback = "could not ask"),
            transcribeInput(),
            context,
        )

        assertEquals("could not ask", out.answer())
        assertTrue(logs.any { it.level == LogLevel.ERROR })
    }

    @Test
    fun `a cut-off reply still reaches the port and warns`() = runBlocking {
        ai.reply = AiReply(text = "they said ye", truncated = true)

        val out = action.executeRaw(TranscribeConfig(modelRef = MODEL), transcribeInput(), context)

        assertEquals("they said ye", out.answer())
        assertTrue(logs.any { it.level == LogLevel.WARN })
    }

    private companion object {
        const val MODEL = "profile-1"
    }
}
