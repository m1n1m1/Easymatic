package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.ListenOutcome
import com.example.ottomatic.core.service.ListenRequest
import com.example.ottomatic.core.service.LogEntry
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.NoSpeech
import com.example.ottomatic.core.service.Speech
import com.example.ottomatic.core.service.SpeechOutcome
import com.example.ottomatic.core.service.SpeechRequest
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.engine.DefaultExecutionContext
import com.example.ottomatic.engine.ExecutionRoute
import com.example.ottomatic.engine.NodeInput
import com.example.ottomatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What each ending of a listening attempt costs the graph and the console.
 *
 * The routing is where the three-outcomes-two-log-levels design actually lives, and it is
 * pure — no recognizer, no platform, no device — so it is worth pinning here rather than
 * discovering on a phone. Two things are asserted every time and they are separate claims:
 * **which branch fires**, which is what the user wired, and **what lands on the port**,
 * which is what a downstream node reads.
 */
class ListenRoutingTest {

    private val logs = mutableListOf<LogEntry>()

    private fun contextFor(outcome: ListenOutcome) = DefaultExecutionContext(
        systemServices = RecordingSystemServices(),
        speech = object : Speech by NoSpeech {
            override suspend fun listen(request: ListenRequest) = outcome
        },
        logger = { logs += it },
    )

    private suspend fun run(outcome: ListenOutcome) =
        ListenAction().executeRaw(ListenConfig(), NodeInput(node, emptyMap()), contextFor(outcome))

    private val node = WorkflowNode(
        id = NodeId("listen"),
        typeId = ListenAction().definition.typeId,
        name = "Listen",
        x = 0f,
        y = 0f,
    )

    @Test
    fun `something heard takes the heard branch and lands on the port`() = runBlocking {
        val output = run(ListenOutcome(heard = true, text = "turn the lights off"))

        assertEquals(ExecutionRoute.HEARD, output.route)
        assertEquals("turn the lights off", output.value[LISTEN_TEXT_OUT]?.value)
    }

    /**
     * The rule this shares with `action.dialog_input`, and the reason it is a test rather
     * than a comment: a blank string on the port would be *readable* downstream, so a node
     * wired from both branches would quietly act on an answer nobody gave.
     */
    @Test
    fun `silence takes the nothing branch and publishes no value at all`() = runBlocking {
        val output = run(ListenOutcome())

        assertEquals(ExecutionRoute.NOTHING_HEARD, output.route)
        assertTrue("nothing should be on the port, not a blank", output.value.isEmpty())
    }

    @Test
    fun `running out of time is its own branch, not silence`() = runBlocking {
        val output = run(ListenOutcome(timedOut = true))

        assertEquals(ExecutionRoute.TIMED_OUT, output.route)
        assertTrue(output.value.isEmpty())
    }

    /**
     * A refused recognizer joins silence on the *graph* — both mean "carry on without an
     * answer" — and separates from it in the *console*, because one is something the user
     * can fix and the other is somebody having walked away.
     */
    @Test
    fun `a refused recognizer shares the branch but not the log level`() = runBlocking {
        val output = run(ListenOutcome(error = "This phone has no speech recognition"))

        assertEquals(ExecutionRoute.NOTHING_HEARD, output.route)
        assertTrue(output.value.isEmpty())
        assertEquals(LogLevel.WARN, logs.single().level)
        assertTrue(logs.single().message.contains("no speech recognition"))
    }

    @Test
    fun `silence is reported without a warning`() = runBlocking {
        run(ListenOutcome())

        assertEquals(LogLevel.INFO, logs.single().level)
    }

    /**
     * The default is a fact about the node rather than about the facade, and it is the one
     * a user is most likely to be surprised by: a listening node dropped on the canvas and
     * left alone must stop on its own.
     */
    @Test
    fun `an unconfigured node still gives up, and prefers the on-device recognizer`() {
        val config = ListenConfig()

        assertTrue("a recognizer nobody stops holds the microphone", config.maxSeconds > 0)
        assertTrue(config.preferOffline)
        assertEquals("end-of-speech is the platform's job by default", 0, config.silenceSeconds)
    }
}

/**
 * `action.speak`'s one decision that is not the facade's: waiting by default.
 *
 * Pinned because it is load-bearing and invisible — the whole reason it is true is the node
 * *after* this one, so nothing in this file's own behaviour would reveal a regression.
 */
class SpeakDefaultsTest {

    @Test
    fun `speaking waits by default, so a listen node after it does not hear the phone`() {
        assertTrue(SpeakConfig().waitForCompletion)
    }

    @Test
    fun `and it replaces rather than queues, so a re-fired macro does not stack up`() {
        assertTrue(!SpeakConfig().queue)
    }

    @Test
    fun `a blank language means the phone's own, which is what an untouched node has`() {
        assertEquals("", SpeakConfig().language)
    }

    /** Nothing about speaking needs a grant; the capability is a fact about the phone. */
    @Test
    fun `it declares no permission`() {
        assertTrue(SpeakAction().definition.permissions.isEmpty())
        assertTrue(SpeakStopAction().definition.permissions.isEmpty())
    }

    /**
     * The facade must answer rather than throw, because a node reports and carries on. The
     * default object is what an engine-only test and a phone with no engine both see.
     */
    @Test
    fun `the no-op facade fails closed with a sentence`() = runBlocking {
        val spoken = NoSpeech.speak(SpeechRequest(text = "hello"))
        assertEquals(SpeechOutcome(error = spoken.error), spoken)
        assertTrue(spoken.error.isNotBlank())
        assertTrue(NoSpeech.listen(ListenRequest()).error.isNotBlank())
        assertTrue(!NoSpeech.isSpeaking())
        assertTrue(!NoSpeech.stop())
    }
}
