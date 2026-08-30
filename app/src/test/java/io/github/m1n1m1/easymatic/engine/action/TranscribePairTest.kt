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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The start/stop listening pair, and the division of labour between the two nodes.
 *
 * **The split is not symmetric, and that is what this pins.** Start carries only the two
 * numbers that bound the listening; the model, the question, the reply bound and the
 * fallback all live on stop, because that is where an answer comes out. A **Model** field
 * on the start node would be asking a question at the moment there is nothing yet to ask
 * about, and would leave two model fields on one pair to disagree.
 *
 * The rest is `action.record_start` / `action.record_stop`'s contract one family along: a
 * refused microphone is reported and the macro carries on, and a stop nobody started says
 * so rather than answering silently.
 */
class TranscribePairTest {

    private val logs = mutableListOf<LogEntry>()
    private val ai = FakeAi()
    private val microphone = RecordingMicrophone()
    private val context = DefaultExecutionContext(
        systemServices = RecordingSystemServices(),
        ai = ai,
        microphone = microphone,
        logger = { logs += it },
    )
    private val start = TranscribeStartAction()
    private val stop = TranscribeEndAction()

    @Test
    fun `starting opens the microphone with the configured bounds and asks nothing`() = runBlocking {
        start.execute(TranscribeStartConfig(maxSeconds = 90, silenceSeconds = 4), context)

        val asked = microphone.begunCaptures.single()
        assertEquals(90, asked.maxSeconds)
        assertEquals(4, asked.silenceSeconds)
        assertEquals(emptyList<Any>(), ai.requests)
    }

    /**
     * The opposite default from `action.ai_listen`: there a fixed span is the whole node
     * and quiet is the natural way to end early, where here the macro has said it will
     * decide when to stop, so ending on a pause would take the decision back.
     */
    @Test
    fun `starting does not stop on silence unless asked to`() = runBlocking {
        start.execute(TranscribeStartConfig(), context)

        assertEquals(0, microphone.begunCaptures.single().silenceSeconds)
    }

    @Test
    fun `a microphone that is busy is reported and the macro carries on`() = runBlocking {
        microphone.captureProblem = "A recording is already running"

        val out = start.execute(TranscribeStartConfig(), context)

        assertEquals(Unit, out.value)
        assertTrue(logs.any { it.level == LogLevel.ERROR && it.message.contains("already running") })
    }

    @Test
    fun `stopping collects the clip and sends it to the model`() = runBlocking {
        ai.reply = AiReply(text = "they said yes")

        val out = stop.executeRaw(TranscribeEndConfig(), transcribeInput(), context)

        assertEquals("they said yes", out.answer())
        assertEquals(1, microphone.endedCaptures)
        val clip = ai.requests.single().audio.single()
        assertEquals("AAAA", clip.base64)
        assertEquals("audio/wav", clip.mediaType)
    }

    @Test
    fun `an empty question reaches the facade empty`() = runBlocking {
        stop.executeRaw(TranscribeEndConfig(), transcribeInput(), context)

        assertEquals("", ai.requests.single().prompt)
    }

    /** `action.record_stop`'s rule: a stop nobody started is worth reporting. */
    @Test
    fun `stopping what was never started lands on the fallback and says so`() = runBlocking {
        microphone.captured = CaptureOutcome(error = "Nothing is listening")

        val out = stop.executeRaw(TranscribeEndConfig(fallback = "idle"), transcribeInput(), context)

        assertEquals("idle", out.answer())
        assertEquals(emptyList<Any>(), ai.requests)
        assertTrue(logs.any { it.level == LogLevel.ERROR && it.message.contains("Nothing is listening") })
    }

    /** Shared with `action.ai_listen` through `askAbout`, so the two cannot disagree. */
    @Test
    fun `a quiet room is not an error on this node either`() = runBlocking {
        microphone.captured = CaptureOutcome(base64 = "AAAA", mediaType = "audio/wav", heard = false)

        val out = stop.executeRaw(
            TranscribeEndConfig(fallback = "silence"),
            transcribeInput(),
            context,
        )

        assertEquals("silence", out.answer())
        assertEquals(emptyList<Any>(), ai.requests)
        assertTrue(logs.none { it.level == LogLevel.ERROR })
    }

    private companion object {
        const val MODEL = "profile-1"
    }
}
