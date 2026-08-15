package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.AiReply
import com.example.ottomatic.core.service.FileBytes
import com.example.ottomatic.core.service.LogEntry
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.engine.DefaultExecutionContext
import com.example.ottomatic.engine.ExecutionRoute
import com.example.ottomatic.engine.FakeAi
import com.example.ottomatic.engine.RecordingFiles
import com.example.ottomatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `action.ai_describe` — the one AI node that needed something of the platform.
 *
 * Everything else here is built out of what the app already had; this one brought
 * `Files.readBytes` and one image rendering per protocol with it. So what is pinned is
 * the seam: the picture is read through the same facade every file node uses, and it
 * reaches the model as an image rather than as text.
 */
class AiDescribeActionTest {

    private val logs = mutableListOf<LogEntry>()
    private val ai = FakeAi()
    private val files = RecordingFiles()
    private val context = DefaultExecutionContext(
        systemServices = RecordingSystemServices(),
        ai = ai,
        files = files,
        logger = { logs += it },
    )
    private val action = AiDescribeAction()

    @Test
    fun `the picture is read through the files facade and sent as an image`() = runBlocking {
        ai.reply = AiReply(text = "a cat")
        val out = action.execute(
            AiDescribeConfig(connectionId = CONNECTION, image = "/DCIM/cat.jpg", prompt = "what is this?"),
            context,
        )
        assertEquals("a cat", out.value)
        assertEquals("readBytes", files.calls.single().member)
        val image = ai.requests.single().images.single()
        assertEquals("AAAA", image.base64)
        assertEquals("image/jpeg", image.mediaType)
    }

    /**
     * Every provider requires the media type, and one guessed wrong comes back as a
     * generic 400 naming neither the file nor the reason — so the refusal happens here.
     */
    @Test
    fun `a file that is not a known picture kind is refused before the network`() = runBlocking {
        files.bytes = FileBytes(base64 = "AAAA", mediaType = "")
        val out = action.execute(
            AiDescribeConfig(connectionId = CONNECTION, image = "/notes.txt", fallback = "no picture"),
            context,
        )
        assertEquals("no picture", out.value)
        assertEquals(emptyList<Any>(), ai.requests)
        assertTrue(logs.any { it.message.contains("JPEG, PNG, GIF or WebP") })
    }

    @Test
    fun `an unreadable file lands on the fallback and says why`() = runBlocking {
        files.bytes = FileBytes(error = "There is no file at /DCIM/cat.jpg")
        val out = action.execute(
            AiDescribeConfig(connectionId = CONNECTION, image = "/DCIM/cat.jpg", fallback = "nothing"),
            context,
        )
        assertEquals("nothing", out.value)
        assertTrue(logs.any { it.message.contains("no file at") })
    }

    @Test
    fun `no picture chosen never reaches the facade`() = runBlocking {
        val out = action.execute(AiDescribeConfig(connectionId = CONNECTION, fallback = "idle"), context)
        assertEquals("idle", out.value)
        assertEquals(emptyList<Any>(), files.calls)
        assertEquals(emptyList<Any>(), ai.requests)
    }

    /** `action.script`'s stance: a failure is reported, not fatal. */
    @Test
    fun `a refused request lands on the fallback and still pulses out`() = runBlocking {
        ai.reply = AiReply(error = "API key not valid")
        val out = action.execute(
            AiDescribeConfig(connectionId = CONNECTION, image = "/DCIM/cat.jpg", fallback = "could not look"),
            context,
        )
        assertEquals("could not look", out.value)
        assertEquals(ExecutionRoute.OUT, out.route)
        assertFalse(out.halt)
        assertTrue(logs.any { it.level == LogLevel.ERROR && it.message.contains("API key not valid") })
    }

    private companion object {
        const val CONNECTION = "connection-id"
    }
}
