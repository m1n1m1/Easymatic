package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.ListenOutcome
import io.github.m1n1m1.easymatic.core.service.ListenRequest
import io.github.m1n1m1.easymatic.core.service.LogEntry
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.core.service.NoSpeech
import io.github.m1n1m1.easymatic.core.service.Speech
import io.github.m1n1m1.easymatic.engine.DefaultExecutionContext
import io.github.m1n1m1.easymatic.engine.FakeAi
import io.github.m1n1m1.easymatic.engine.RecordingMicrophone
import io.github.m1n1m1.easymatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offline half of the transcription family: the phone's own recogniser instead of a
 * model.
 *
 * **What this pins above all is that choosing the phone reaches no model at all.** The
 * failure it guards against is not a crash but a bill: a node set to "This phone, offline"
 * that still called `Ai.complete` would work perfectly, return sensible text, and quietly
 * charge for every run — and nothing on the card or in the console would say so. So every
 * offline case here asserts `ai.requests` is empty as well as asserting the answer.
 *
 * The microphone is asserted untouched for the same reason from the other side: the offline
 * path goes through `Speech`, which opens the recogniser itself, so a capture starting as
 * well would hold the hardware twice for one node.
 */
class TranscribeOfflineTest {

    private val logs = mutableListOf<LogEntry>()
    private val ai = FakeAi()
    private val microphone = RecordingMicrophone()
    private val heardRequests = mutableListOf<ListenRequest>()
    private var outcome = ListenOutcome(heard = true, text = "turn the lights off")

    private val context = DefaultExecutionContext(
        systemServices = RecordingSystemServices(),
        ai = ai,
        microphone = microphone,
        speech = object : Speech by NoSpeech {
            override suspend fun listen(request: ListenRequest): ListenOutcome {
                heardRequests += request
                return outcome
            }
        },
        logger = { logs += it },
    )

    private fun offline(
        languageMode: TranscribeLanguage = TranscribeLanguage.PHONE,
        language: String = "",
    ) = TranscribeConfig(
        using = TranscribeUsing.PHONE,
        languageMode = languageMode,
        language = language,
        maxSeconds = 20,
        silenceSeconds = 2,
        fallback = "nothing",
    )

    @Test
    fun `the phone transcribes and no model is billed`() = runBlocking {
        val out = TranscribeAction().executeRaw(offline(), transcribeInput(), context)

        assertEquals("turn the lights off", out.answer())
        assertEquals(emptyList<Any>(), ai.requests)
        assertEquals(emptyList<Any>(), microphone.captures)
    }

    /** The listening bounds are the node's, whichever engine is chosen. */
    @Test
    fun `a chosen language and the bounds reach the recogniser`() = runBlocking {
        TranscribeAction().executeRaw(
            offline(TranscribeLanguage.CHOSEN, "de-DE"),
            transcribeInput(),
            context,
        )

        val asked = heardRequests.single()
        assertEquals("de-DE", asked.language)
        assertFalse(asked.detect)
        assertEquals(20, asked.maxSeconds)
        assertEquals(2, asked.silenceSeconds)
        // The whole point of choosing this engine is that nothing leaves the phone.
        assertTrue(asked.preferOffline)
    }

    /**
     * **"Listen the whole time" has to be *said*, not inferred from the number.**
     *
     * Zero means "let the phone decide" to `action.listen`, where the platform's own
     * end-of-speech detection is right, and "do not stop early" here. Reading the intent off
     * the number is what made a session configured never to stop end at the first pause, so
     * the request carries the intent explicitly and this pins that it does.
     */
    @Test
    fun `no silence asks the facade to keep listening across pauses`() = runBlocking {
        TranscribeAction().executeRaw(
            offline().copy(silenceSeconds = 0),
            transcribeInput(),
            context,
        )

        assertTrue(heardRequests.single().continuous)
    }

    @Test
    fun `a stated silence lets the utterance end on its own`() = runBlocking {
        TranscribeAction().executeRaw(
            offline().copy(silenceSeconds = 3),
            transcribeInput(),
            context,
        )

        assertFalse(heardRequests.single().continuous)
    }

    /**
     * **The three language modes, which replaced a text box whose blank meant something.**
     *
     * The distinction that matters is between the two that send *no* language: the phone's
     * own is the recogniser's default, and detection starts from that same default and is
     * allowed to move off it. A mode that sent a language while asking for detection would
     * be asking the recogniser to both stay and switch.
     */
    @Test
    fun `the phone's own language names none and asks for no detection`() = runBlocking {
        TranscribeAction().executeRaw(offline(TranscribeLanguage.PHONE), transcribeInput(), context)

        val asked = heardRequests.single()
        assertEquals("", asked.language)
        assertFalse(asked.detect)
    }

    @Test
    fun `detect asks for detection and still names no language`() = runBlocking {
        TranscribeAction().executeRaw(offline(TranscribeLanguage.DETECT), transcribeInput(), context)

        val asked = heardRequests.single()
        assertEquals("", asked.language)
        assertTrue(asked.detect)
    }

    /** A tag left in the field from an earlier choice must not leak into the other modes. */
    @Test
    fun `a stale chosen tag is ignored unless the mode asks for it`() = runBlocking {
        TranscribeAction().executeRaw(
            offline(TranscribeLanguage.DETECT, "de-DE"),
            transcribeInput(),
            context,
        )

        assertEquals("", heardRequests.single().language)
    }

    // ---- what comes back --------------------------------------------------------

    @Test
    fun `the detected language lands on its own port`() = runBlocking {
        outcome = ListenOutcome(heard = true, text = "guten Morgen", language = "de-DE")

        val out = TranscribeAction().executeRaw(offline(TranscribeLanguage.DETECT), transcribeInput(), context)

        assertEquals("guten Morgen", out.answer())
        assertEquals("de-DE", out.detectedLanguage())
    }

    /**
     * **Null, not blank.** Nothing reports a language below Android 14 or on an unsupporting
     * recogniser, and `action.listen`'s rule is that a port nobody can answer carries
     * nothing — a blank string there is a value a downstream node would happily read.
     */
    @Test
    fun `an unreported language leaves the port empty rather than blank`() = runBlocking {
        outcome = ListenOutcome(heard = true, text = "hello")

        val out = TranscribeAction().executeRaw(offline(TranscribeLanguage.DETECT), transcribeInput(), context)

        assertEquals("hello", out.answer())
        assertNull(out.detectedLanguage())
    }

    /** A model does not say what it detected, so the AI path never fills that port. */
    @Test
    fun `the AI path leaves the language port empty`() = runBlocking {
        val out = TranscribeAction().executeRaw(
            TranscribeConfig(modelRef = "profile-1"),
            transcribeInput(),
            context,
        )

        assertNull(out.detectedLanguage())
    }

    /** `askAbout`'s rule, kept by the offline twin: a quiet room is the node working. */
    @Test
    fun `nothing said lands on the fallback without an error`() = runBlocking {
        outcome = ListenOutcome(heard = false)

        val out = TranscribeAction().executeRaw(offline(), transcribeInput(), context)

        assertEquals("nothing", out.answer())
        assertTrue(logs.none { it.level == LogLevel.ERROR })
    }

    @Test
    fun `a timeout lands on the fallback and is not an error either`() = runBlocking {
        outcome = ListenOutcome(timedOut = true)

        val out = TranscribeAction().executeRaw(offline(), transcribeInput(), context)

        assertEquals("nothing", out.answer())
        assertTrue(logs.none { it.level == LogLevel.ERROR })
    }

    /**
     * The missing language pack — the one failure this option adds that the AI path cannot
     * have, and the reason `AndroidSpeech` stopped folding it into "Speech recognition
     * failed".
     */
    @Test
    fun `a refused recogniser is an error and says what it said`() = runBlocking {
        outcome = ListenOutcome(error = "The language pack for that language is not installed")

        val out = TranscribeAction().executeRaw(offline(), transcribeInput(), context)

        assertEquals("nothing", out.answer())
        assertTrue(logs.any { it.level == LogLevel.ERROR && it.message.contains("language pack") })
    }

    /**
     * **The pair's asymmetry, pinned.** Start chooses the engine because it decides which
     * hardware session opens; End carries no engine field and discovers which one is
     * running. A second dropdown on End could disagree with Start, and a user who changed
     * one and not the other would get a node that collects nothing while both cards look
     * correctly filled in.
     */
    @Test
    fun `starting offline opens a recogniser session and no recording`() = runBlocking {
        val speech = SessionSpeech()
        val context = contextWith(speech)

        TranscribeStartAction().execute(
            TranscribeStartConfig(
                using = TranscribeUsing.PHONE,
                languageMode = TranscribeLanguage.CHOSEN,
                language = "en-GB",
                maxSeconds = 45,
            ),
            context,
        )

        assertEquals("en-GB", speech.begun.single().language)
        assertEquals(45, speech.begun.single().maxSeconds)
        assertEquals(emptyList<Any>(), microphone.begunCaptures)
    }

    @Test
    fun `ending collects the recogniser session without asking a model`() = runBlocking {
        val speech = SessionSpeech(running = true)
        val context = contextWith(speech)

        val out = TranscribeEndAction().executeRaw(
            TranscribeEndConfig(),
            transcribeInput(),
            context,
        )

        assertEquals("turn the lights off", out.answer())
        assertEquals(1, speech.ended)
        assertEquals(emptyList<Any>(), ai.requests)
        assertEquals(0, microphone.endedCaptures)
    }

    /** With no recogniser session open, End is the AI node it always was. */
    @Test
    fun `ending an AI session still collects the recording and asks the model`() = runBlocking {
        val speech = SessionSpeech(running = false)
        val context = contextWith(speech)

        TranscribeEndAction().executeRaw(TranscribeEndConfig(), transcribeInput(), context)

        assertEquals(1, microphone.endedCaptures)
        assertEquals(1, ai.requests.size)
        assertEquals(0, speech.ended)
    }

    private fun contextWith(speech: Speech) = DefaultExecutionContext(
        systemServices = RecordingSystemServices(),
        ai = ai,
        microphone = microphone,
        speech = speech,
        logger = { logs += it },
    )

    /** A [Speech] that remembers a session, so the pair's routing can be asserted. */
    private inner class SessionSpeech(private val running: Boolean = false) : Speech by NoSpeech {
        val begun = mutableListOf<ListenRequest>()
        var ended = 0
            private set

        override suspend fun beginListening(request: ListenRequest): String {
            begun += request
            return ""
        }

        override fun isTranscribing(): Boolean = running

        override suspend fun endListening(): ListenOutcome {
            ended++
            return outcome
        }
    }

    /** The switch has to actually switch: the default still goes to a model. */
    @Test
    fun `the AI path is untouched by the new option`() = runBlocking {
        TranscribeAction().executeRaw(TranscribeConfig(modelRef = "profile-1"), transcribeInput(), context)

        assertEquals(1, ai.requests.size)
        assertEquals(emptyList<Any>(), heardRequests)
        assertEquals(1, microphone.captures.size)
    }
}
