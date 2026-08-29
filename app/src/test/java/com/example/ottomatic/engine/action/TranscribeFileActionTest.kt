package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.AiReply
import com.example.ottomatic.core.service.AudioLimits
import com.example.ottomatic.core.service.FileBytes
import com.example.ottomatic.core.service.LogEntry
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.engine.DefaultExecutionContext
import com.example.ottomatic.engine.FakeAi
import com.example.ottomatic.engine.RecordingFiles
import com.example.ottomatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `action.ai_transcribe` — the second AI node that needed something of the platform.
 *
 * **It reads through the file facade rather than the picture one**, which is the opposite
 * call from `action.ai_describe` and is forced by what the thing is. A photo is a media
 * row every gallery on the phone can see, and `Images.encodeForModel` shrinks it by
 * construction; a recording is a file somebody names, with nothing to shrink and no media
 * collection behind it. So this one takes the bound that node could not use, and passes
 * a bigger one than the file layer's default because it is the caller that knows what it
 * is holding.
 *
 * The other half pinned here is the **blank question**. Leaving it empty is a setting
 * rather than a missing field, and it has to reach the facade blank — substituting a
 * default in the node would send every plain transcription down the chat wire instead of
 * the transcription endpoint built for it, silently and on every provider.
 */
class TranscribeFileActionTest {

    private val logs = mutableListOf<LogEntry>()
    private val ai = FakeAi()
    private val files = RecordingFiles(bytes = FileBytes(base64 = "QUFB", mediaType = "audio/wav"))
    private val context = DefaultExecutionContext(
        systemServices = RecordingSystemServices(),
        ai = ai,
        files = files,
        logger = { logs += it },
    )
    private val action = TranscribeFileAction()

    @Test
    fun `the clip is read through the file facade and sent as audio`() = runBlocking {
        ai.reply = AiReply(text = "hello there")

        val out = action.execute(
            TranscribeFileConfig(modelRef = MODEL, audio = "Recordings/note.wav"),
            context,
        )

        assertEquals("hello there", out.value)
        val clip = ai.requests.single().audio.single()
        assertEquals("QUFB", clip.base64)
        assertEquals("audio/wav", clip.mediaType)
    }

    /** The whole reason `readBytes` grew a parameter: one megabyte is nothing in audio. */
    @Test
    fun `it asks for the audio bound rather than the file layer's default`() = runBlocking {
        action.execute(TranscribeFileConfig(modelRef = MODEL, audio = "Recordings/note.wav"), context)

        val call = files.calls.single { it.member == "readBytes" }
        assertEquals(AudioLimits.MAX_MODEL_BYTES, call.maxBytes)
    }

    /**
     * The blank has to survive all the way to the facade. A default substituted here
     * would look identical in every test above and would quietly cost every user the
     * transcription endpoint.
     */
    @Test
    fun `an empty question reaches the facade empty`() = runBlocking {
        action.execute(TranscribeFileConfig(modelRef = MODEL, audio = "Recordings/note.wav"), context)

        assertEquals("", ai.requests.single().prompt)
    }

    @Test
    fun `a question that was asked is passed through`() = runBlocking {
        action.execute(
            TranscribeFileConfig(modelRef = MODEL, audio = "Recordings/note.wav", prompt = "who spoke?"),
            context,
        )

        assertEquals("who spoke?", ai.requests.single().prompt)
    }

    @Test
    fun `no file chosen never reaches either facade`() = runBlocking {
        val out = action.execute(TranscribeFileConfig(modelRef = MODEL, fallback = "idle"), context)

        assertEquals("idle", out.value)
        assertEquals(emptyList<Any>(), files.calls)
        assertEquals(emptyList<Any>(), ai.requests)
    }

    @Test
    fun `an unreadable file lands on the fallback and says why`() = runBlocking {
        files.bytes = FileBytes(error = "There is no file at Recordings/gone.wav")

        val out = action.execute(
            TranscribeFileConfig(modelRef = MODEL, audio = "Recordings/gone.wav", fallback = "nothing"),
            context,
        )

        assertEquals("nothing", out.value)
        assertTrue(logs.any { it.message.contains("no file at") })
        assertEquals(emptyList<Any>(), ai.requests)
    }

    /**
     * A guessed media type comes back from a provider as a 400 naming neither the file
     * nor the reason, and the extension is the only thing the user can actually change.
     */
    @Test
    fun `a name with no known sound extension is refused before the network`() = runBlocking {
        files.bytes = FileBytes(base64 = "QUFB", mediaType = "")

        val out = action.execute(
            TranscribeFileConfig(modelRef = MODEL, audio = "Recordings/note.xyz", fallback = "no sound"),
            context,
        )

        assertEquals("no sound", out.value)
        assertEquals(emptyList<Any>(), ai.requests)
        assertTrue(logs.any { it.message.contains("note.xyz") })
    }

    /** `action.script`'s stance: a failure is reported, not fatal. */
    @Test
    fun `a refused request lands on the fallback and still pulses out`() = runBlocking {
        ai.reply = AiReply(error = "Claude cannot listen to audio")

        val out = action.execute(
            TranscribeFileConfig(modelRef = MODEL, audio = "Recordings/note.wav", fallback = "could not hear"),
            context,
        )

        assertEquals("could not hear", out.value)
        assertTrue(logs.any { it.level == LogLevel.ERROR && it.message.contains("cannot listen") })
    }

    /** Real output a macro will send on, so it reaches the port and the console says so. */
    @Test
    fun `a cut-off transcript still reaches the port and warns`() = runBlocking {
        ai.reply = AiReply(text = "half a sen", truncated = true)

        val out = action.execute(
            TranscribeFileConfig(modelRef = MODEL, audio = "Recordings/note.wav"),
            context,
        )

        assertEquals("half a sen", out.value)
        assertTrue(logs.any { it.level == LogLevel.WARN })
    }

    private companion object {
        const val MODEL = "profile-1"
    }
}
