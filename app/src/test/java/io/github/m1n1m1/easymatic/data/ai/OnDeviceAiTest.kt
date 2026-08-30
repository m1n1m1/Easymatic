package io.github.m1n1m1.easymatic.data.ai

import io.github.m1n1m1.easymatic.core.service.AiAudio
import io.github.m1n1m1.easymatic.core.service.AiImage
import io.github.m1n1m1.easymatic.core.service.AiModel
import io.github.m1n1m1.easymatic.core.service.AiRequest
import io.github.m1n1m1.easymatic.domain.model.AiConnection
import io.github.m1n1m1.easymatic.domain.model.AiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole of the on-device path that can be decided without a phone: what a request
 * becomes, and every reason one cannot run here.
 *
 * This is the file `OnDeviceAi`'s KDoc argues for. A device that has no AICore, a model
 * mid-download and an unsupported chip cannot be produced on demand, and the interesting
 * behaviour — *which* of six refusals applies, and therefore which sentence somebody
 * reads — is a pure function of a request and a status. It is a JVM test or it is untested.
 */
class OnDeviceAiTest {

    private val connection = AiConnection(id = "c", name = "On-device", provider = AiProvider.ML_KIT)

    private fun request(
        prompt: String = "hi",
        images: List<AiImage> = emptyList(),
        audio: List<AiAudio> = emptyList(),
    ) = AiRequest(modelRef = "c#p", prompt = prompt, images = images, audio = audio)

    private fun picture(name: String = "AAAA") = AiImage(base64 = name, mediaType = "image/png")

    // ---- what a request becomes ------------------------------------------------

    @Test
    fun `the plan carries the prompt, the standing instruction and the bound`() {
        val plan = mlKitPlan(
            request().copy(systemInstruction = "Be terse", maxOutputTokens = 64),
            target(connection),
        )
        assertEquals("hi", plan.prompt)
        assertEquals("Be terse", plan.systemInstruction)
        assertEquals(64, plan.maxOutputTokens)
        assertNull(plan.image)
    }

    @Test
    fun `the picture reaches the plan`() {
        val plan = mlKitPlan(request(images = listOf(picture("QUJD"))), target(connection))
        assertEquals("QUJD", plan.image?.base64)
    }

    /**
     * The tier chooses the model and nothing else, which is the whole of what it can
     * honestly mean on a phone with one model family and no reasoning budget. A
     * temperature that moved with it would be a knob pretending to be a trade-off.
     */
    @Test
    fun `the tier picks the model and leaves the sampling alone`() {
        val fast = mlKitPlan(request(), target(connection, effort = AiModel.FAST))
        val balanced = mlKitPlan(request(), target(connection, effort = AiModel.BALANCED))
        val thorough = mlKitPlan(request(), target(connection, effort = AiModel.THOROUGH))
        assertFalse(fast.preferFullModel)
        assertTrue(balanced.preferFullModel)
        assertTrue(thorough.preferFullModel)
        assertEquals(fast.temperature, balanced.temperature, 0f)
        assertEquals(fast.topK, thorough.topK)
    }

    /**
     * `GeminiProtocol` and `AnthropicProtocol` both add headroom because thinking tokens
     * come out of the reply's budget there. ML Kit has no such field, so a request asking
     * for 64 tokens must ask for 64 — pinned so nobody copies the headroom across.
     */
    @Test
    fun `no thinking headroom is added to the bound`() {
        AiModel.entries.forEach { effort ->
            val plan = mlKitPlan(request().copy(maxOutputTokens = 200), target(connection, effort = effort))
            assertEquals(200, plan.maxOutputTokens)
        }
    }

    @Test
    fun `a bound of zero becomes a bound of one rather than a request for nothing`() {
        assertEquals(1, mlKitPlan(request().copy(maxOutputTokens = 0), target(connection)).maxOutputTokens)
    }

    // ---- every reason a request cannot run here --------------------------------

    @Test
    fun `an ordinary prompt on a ready phone has no problem`() {
        assertNull(onDeviceProblem(request(), OnDeviceStatus.AVAILABLE, wantsTools = false))
    }

    @Test
    fun `one picture on a ready phone has no problem`() {
        assertNull(
            onDeviceProblem(request(images = listOf(picture())), OnDeviceStatus.AVAILABLE, wantsTools = false),
        )
    }

    /**
     * Four states, four sentences — and the point of the enum having four members rather
     * than being a boolean. "This phone cannot run it" in front of somebody whose download
     * is at ninety per cent is the failure this pins against.
     */
    @Test
    fun `each unready state names its own fix`() {
        val unsupported = onDeviceProblem(request(), OnDeviceStatus.UNSUPPORTED, wantsTools = false)
        val downloadable = onDeviceProblem(request(), OnDeviceStatus.DOWNLOADABLE, wantsTools = false)
        val downloading = onDeviceProblem(request(), OnDeviceStatus.DOWNLOADING, wantsTools = false)
        assertNotNull(unsupported)
        assertNotNull(downloadable)
        assertNotNull(downloading)
        assertEquals(3, setOf(unsupported, downloadable, downloading).size)
        assertTrue(downloadable!!.contains("download", ignoreCase = true))
    }

    /**
     * The Prompt API has no function calling at all, so this holds whatever the phone can
     * otherwise do — and it is checked before the media bounds, because a tool-using node
     * fails whatever it is carrying and "it cannot use tools" is the sentence that names
     * what to change.
     */
    @Test
    fun `tools are refused even on a ready phone, and before the media bounds`() {
        val ready = onDeviceProblem(request(), OnDeviceStatus.AVAILABLE, wantsTools = true)
        assertNotNull(ready)
        assertTrue(ready!!.contains("tools"))
        val alsoAudio = onDeviceProblem(
            request(audio = listOf(AiAudio(base64 = "AA", mediaType = "audio/wav"))),
            OnDeviceStatus.AVAILABLE,
            wantsTools = true,
        )
        assertEquals(ready, alsoAudio)
    }

    @Test
    fun `sound is refused, because the Prompt API is text and pictures`() {
        val problem = onDeviceProblem(
            request(audio = listOf(AiAudio(base64 = "AA", mediaType = "audio/wav"))),
            OnDeviceStatus.AVAILABLE,
            wantsTools = false,
        )
        assertNotNull(problem)
        assertTrue(problem!!.contains("audio"))
    }

    /**
     * ML Kit's request builder takes at most one picture. Refusing the second here is what
     * lets `MlKitPlan` hold one without ever silently dropping anything.
     */
    @Test
    fun `a second picture is refused rather than dropped`() {
        assertNull(
            onDeviceProblem(request(images = listOf(picture())), OnDeviceStatus.AVAILABLE, wantsTools = false),
        )
        assertNotNull(
            onDeviceProblem(
                request(images = listOf(picture("A"), picture("B"))),
                OnDeviceStatus.AVAILABLE,
                wantsTools = false,
            ),
        )
    }

    /** An unsupported phone answers the same way whatever the request carries. */
    @Test
    fun `an unsupported phone reports the phone rather than the request`() {
        val plain = onDeviceProblem(request(), OnDeviceStatus.UNSUPPORTED, wantsTools = false)
        val loaded = onDeviceProblem(
            request(images = listOf(picture("A"), picture("B"))),
            OnDeviceStatus.UNSUPPORTED,
            wantsTools = true,
        )
        assertEquals(plain, loaded)
    }
}
