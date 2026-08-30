package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.AiReply
import io.github.m1n1m1.easymatic.core.service.ImageEncoded
import io.github.m1n1m1.easymatic.core.service.LogEntry
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.engine.DefaultExecutionContext
import io.github.m1n1m1.easymatic.engine.ExecutionRoute
import io.github.m1n1m1.easymatic.engine.FakeAi
import io.github.m1n1m1.easymatic.engine.RecordingImages
import io.github.m1n1m1.easymatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `action.ai_describe` — the one AI node that needed something of the platform.
 *
 * **It reads through the picture facade rather than the file one**, and that is what this
 * pins. It used to call `Files.readBytes`, which was wrong twice over and failed on
 * essentially every real photo: that member refuses anything over
 * `FileLimits.MAX_READ_BYTES` — one megabyte, where a phone camera produces four — and it
 * reaches an absolute path only through a *folder* grant the user has very likely not
 * given, even though the same picture is a media row needing none. `Images.encodeForModel`
 * has neither problem, because it shrinks by construction rather than refusing by size.
 */
class AiDescribeActionTest {

    private val logs = mutableListOf<LogEntry>()
    private val ai = FakeAi()
    private val images = RecordingImages()
    private val context = DefaultExecutionContext(
        systemServices = RecordingSystemServices(),
        ai = ai,
        images = images,
        logger = { logs += it },
    )
    private val action = AiDescribeAction()

    @Test
    fun `the picture is prepared through the picture facade and sent as an image`() = runBlocking {
        ai.reply = AiReply(text = "a cat")
        val out = action.execute(
            AiDescribeConfig(modelRef = MODEL, image = "/DCIM/cat.jpg", prompt = "what is this?"),
            context,
        )
        assertEquals("a cat", out.value)
        assertEquals(listOf("/DCIM/cat.jpg"), images.encodedFor)
        val image = ai.requests.single().images.single()
        assertEquals("AAAA", image.base64)
        assertEquals("image/jpeg", image.mediaType)
    }

    /**
     * The bug this node had until 2026-08-16: a four-megabyte camera photo was refused
     * outright. It is now shrunk on the way, which is what every provider does server-side
     * anyway — so the size that used to fail is the ordinary case.
     */
    @Test
    fun `a large photo is shrunk rather than refused`() = runBlocking {
        images.encoded = ImageEncoded(
            base64 = "AAAA",
            mediaType = "image/jpeg",
            width = 1568,
            height = 1176,
            shrunk = true,
            sourceWidth = 4032,
            sourceHeight = 3024,
        )
        ai.reply = AiReply(text = "a cat")

        val out = action.execute(
            AiDescribeConfig(modelRef = MODEL, image = "/DCIM/cat.jpg"),
            context,
        )

        assertEquals("a cat", out.value)
        assertEquals(1, ai.requests.size)
    }

    /** Quietly shrinking is right; quietly *not saying so* is how "it missed the small print" becomes unanswerable. */
    @Test
    fun `the shrink is said out loud`() = runBlocking {
        images.encoded = ImageEncoded(
            base64 = "AAAA", mediaType = "image/jpeg",
            width = 1568, height = 1176, shrunk = true, sourceWidth = 4032, sourceHeight = 3024,
        )
        action.execute(AiDescribeConfig(modelRef = MODEL, image = "/DCIM/cat.jpg"), context)

        assertTrue(
            logs.any { it.message.contains("4032") && it.message.contains("1568") },
        )
    }

    @Test
    fun `a picture that did not need shrinking says nothing about it`() = runBlocking {
        action.execute(AiDescribeConfig(modelRef = MODEL, image = "/DCIM/small.jpg"), context)

        assertFalse(logs.any { it.message.contains("Shrank") })
    }

    @Test
    fun `an unreadable picture lands on the fallback and says why`() = runBlocking {
        images.encoded = ImageEncoded(error = "There is no picture there")
        val out = action.execute(
            AiDescribeConfig(modelRef = MODEL, image = "/DCIM/cat.jpg", fallback = "nothing"),
            context,
        )
        assertEquals("nothing", out.value)
        assertTrue(logs.any { it.message.contains("no picture there") })
        assertEquals(emptyList<Any>(), ai.requests)
    }

    @Test
    fun `a file that is not a picture is refused before the network`() = runBlocking {
        images.encoded = ImageEncoded(error = "That file is not a picture Android can read")
        val out = action.execute(
            AiDescribeConfig(modelRef = MODEL, image = "/notes.txt", fallback = "no picture"),
            context,
        )
        assertEquals("no picture", out.value)
        assertEquals(emptyList<Any>(), ai.requests)
    }

    @Test
    fun `no picture chosen never reaches the facade`() = runBlocking {
        val out = action.execute(AiDescribeConfig(modelRef = MODEL, fallback = "idle"), context)
        assertEquals("idle", out.value)
        assertEquals(0, images.calls)
        assertEquals(emptyList<Any>(), ai.requests)
    }

    /** `action.script`'s stance: a failure is reported, not fatal. */
    @Test
    fun `a refused request lands on the fallback and still pulses out`() = runBlocking {
        ai.reply = AiReply(error = "API key not valid")
        val out = action.execute(
            AiDescribeConfig(modelRef = MODEL, image = "/DCIM/cat.jpg", fallback = "could not look"),
            context,
        )
        assertEquals("could not look", out.value)
        assertEquals(ExecutionRoute.OUT, out.route)
        assertFalse(out.halt)
        assertTrue(logs.any { it.level == LogLevel.ERROR && it.message.contains("API key not valid") })
    }

    private companion object {
        const val MODEL = "model-profile-id"
    }
}
