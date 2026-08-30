package io.github.m1n1m1.easymatic.data.ai

import io.github.m1n1m1.easymatic.core.service.AiAudio
import io.github.m1n1m1.easymatic.core.service.AiRequest
import io.github.m1n1m1.easymatic.domain.model.AiConnection
import io.github.m1n1m1.easymatic.domain.model.AiProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How sound reaches each provider, and which of them will not take it.
 *
 * **This is the first capability question this package has had to answer, and pictures
 * never posed it.** Every provider that sees images at all accepts the same four image
 * types, so an image's media type never decided whether a request was sendable — which
 * is why `action.ai_describe` can hand any picture to any model and let a server that
 * cannot see say so in its own words. Sound is the opposite in every direction: Claude
 * has no audio block, Gemini refuses `audio/mp4` (which is exactly what this app's own
 * recorder writes), and OpenAI's chat endpoint takes two formats where its transcription
 * endpoint takes eight. Those are facts about the protocol, so leaving them to the server
 * means a 400 naming neither the file nor the reason.
 *
 * The other half pinned here is [audioWireFor], which is one function precisely so that
 * "which wire?" and "is this sendable?" cannot drift apart — a split would refuse an
 * `.m4a` for being unsendable on the one wire that would have taken it.
 */
class AiAudioProtocolTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun connection(provider: AiProvider, baseUrl: String = "") = AiConnection(
        id = "connection-id",
        name = provider.name,
        provider = provider,
        baseUrl = baseUrl,
    )

    private val geminiTarget = target(connection(AiProvider.GEMINI))
    private val claudeTarget = target(connection(AiProvider.ANTHROPIC))
    private val openAiTarget = target(connection(AiProvider.OPENAI))
    private val openRouterTarget = target(connection(AiProvider.OPENROUTER), modelId = "google/gemini-3.6-flash")
    private val selfHostedTarget = target(
        connection(AiProvider.OPENAI_COMPATIBLE, baseUrl = "http://192.168.1.10:8000/v1"),
        modelId = "whisper-1",
    )

    private fun ask(prompt: String = "what was said?", mediaType: String = "audio/wav") = AiRequest(
        modelRef = TEST_MODEL_REF,
        prompt = prompt,
        maxOutputTokens = 100,
        audio = listOf(AiAudio(base64 = "QUFB", mediaType = mediaType)),
    )

    // ---- Gemini ----------------------------------------------------------------

    @Test
    fun `gemini carries sound in the same inline part a picture uses`() {
        val body = json.parseToJsonElement(GeminiProtocol.requestBody(ask(), geminiTarget)).jsonObject
        val parts = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        val inline = parts[0].jsonObject["inlineData"]!!.jsonObject

        assertEquals("audio/wav", inline["mimeType"]!!.jsonPrimitive.content)
        assertEquals("QUFB", inline["data"]!!.jsonPrimitive.content)
    }

    /**
     * The blank is filled in here rather than in the node, because [audioWireFor] reads
     * the blank to mean "just transcribe it". Substituting earlier would send every plain
     * transcription down the chat wire on every provider.
     */
    @Test
    fun `gemini spells out the instruction when there is no question`() {
        val body = json.parseToJsonElement(GeminiProtocol.requestBody(ask(prompt = ""), geminiTarget)).jsonObject
        val parts = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        val text = parts.last().jsonObject["text"]!!.jsonPrimitive.content

        assertTrue(text.contains("Transcribe", ignoreCase = true))
    }

    @Test
    fun `a blank prompt with no audio is still sent blank`() {
        val request = AiRequest(modelRef = TEST_MODEL_REF, prompt = "", maxOutputTokens = 100)
        val body = json.parseToJsonElement(GeminiProtocol.requestBody(request, geminiTarget)).jsonObject
        val parts = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray

        assertEquals("", parts.last().jsonObject["text"]!!.jsonPrimitive.content)
    }

    /**
     * The instruction has to *forbid the framing*, not merely ask for the words.
     *
     * A model handed a clip and "transcribe this" reliably answers "Sure! Here is the
     * transcript: …" with a closing remark, which is not a transcript and is exactly what
     * a macro then mails to somebody.
     */
    @Test
    fun `the transcription instruction rules out commentary as well as asking for words`() {
        val body = json.parseToJsonElement(GeminiProtocol.requestBody(ask(prompt = ""), geminiTarget)).jsonObject
        val parts = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        val text = parts.last().jsonObject["text"]!!.jsonPrimitive.content.lowercase()

        assertTrue(text.contains("only"))
        assertTrue(text.contains("commentary"))
    }

    /**
     * The bug this pair of tests was written for. OpenRouter has audio-capable chat models
     * and **no** transcription endpoint, so a blank question lands on its chat wire — where
     * it used to send an *empty* text part beside the clip. A model given audio and nothing
     * to do with it answers however it likes, and nothing anywhere reported that a
     * transcript was not what came back.
     */
    @Test
    fun `a chat wire with no question still asks for a transcript`() {
        val body = json.parseToJsonElement(
            OpenAiProtocol.OpenRouter.requestBody(ask(prompt = ""), openRouterTarget),
        ).jsonObject
        val content = body["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray
        val text = content.last().jsonObject["text"]!!.jsonPrimitive.content

        assertEquals(TRANSCRIBE_INSTRUCTION, text)
    }

    @Test
    fun `every chat renderer sends the same instruction`() {
        val gemini = json.parseToJsonElement(GeminiProtocol.requestBody(ask(prompt = ""), geminiTarget))
            .jsonObject["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
            .last().jsonObject["text"]!!.jsonPrimitive.content

        assertEquals(TRANSCRIBE_INSTRUCTION, gemini)
    }

    /** A question that was asked must still reach the model unchanged. */
    @Test
    fun `a stated question is never replaced by the instruction`() {
        val body = json.parseToJsonElement(
            OpenAiProtocol.OpenRouter.requestBody(ask(prompt = "was that a yes?"), openRouterTarget),
        ).jsonObject
        val content = body["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray

        assertEquals("was that a yes?", content.last().jsonObject["text"]!!.jsonPrimitive.content)
    }

    /** The format this app's own Record Audio node writes, and the one Gemini refuses. */
    @Test
    fun `gemini refuses mp4 audio by name before the network`() {
        val problem = GeminiProtocol.audioProblem(ask(mediaType = "audio/mp4"), geminiTarget)

        assertNotNull(problem)
        assertTrue(problem!!.contains("audio/mp4"))
    }

    @Test
    fun `gemini accepts the formats it documents`() {
        listOf("audio/wav", "audio/mpeg", "audio/aac", "audio/ogg", "audio/flac", "audio/aiff").forEach {
            assertNull(it, GeminiProtocol.audioProblem(ask(mediaType = it), geminiTarget))
        }
    }

    // ---- Anthropic -------------------------------------------------------------

    @Test
    fun `claude refuses any sound and names what to use instead`() {
        val problem = AnthropicProtocol.audioProblem(ask(), claudeTarget)

        assertNotNull(problem)
        assertTrue(problem!!.contains("Gemini"))
    }

    @Test
    fun `claude is untroubled by a request carrying no sound`() {
        val request = AiRequest(modelRef = TEST_MODEL_REF, prompt = "hi", maxOutputTokens = 100)

        assertNull(AnthropicProtocol.audioProblem(request, claudeTarget))
    }

    // ---- the OpenAI family -----------------------------------------------------

    @Test
    fun `openai sends sound as an input_audio part with a bare format name`() {
        val body = json.parseToJsonElement(OpenAiProtocol.OpenAi.requestBody(ask(), openAiTarget)).jsonObject
        val content = body["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray
        val part = content[0].jsonObject

        assertEquals("input_audio", part["type"]!!.jsonPrimitive.content)
        assertEquals("QUFB", part["input_audio"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        // A closed two-member set, not a media type — the field this API is fussiest about.
        assertEquals("wav", part["input_audio"]!!.jsonObject["format"]!!.jsonPrimitive.content)
    }

    @Test
    fun `mp3 is the other format the chat endpoint knows`() {
        val body = json.parseToJsonElement(
            OpenAiProtocol.OpenAi.requestBody(ask(mediaType = "audio/mpeg"), openAiTarget),
        ).jsonObject
        val content = body["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray

        assertEquals("mp3", content[0].jsonObject["input_audio"]!!.jsonObject["format"]!!.jsonPrimitive.content)
    }

    /**
     * The regression this file exists most to prevent. The string fast path used to test
     * only `images`, so a request carrying sound and no picture rendered as plain text and
     * the clip simply was not on the wire — and the model answered a question about a
     * recording it had never been sent, confidently, with no error anywhere.
     */
    @Test
    fun `sound alone still forces the content array`() {
        val body = json.parseToJsonElement(OpenAiProtocol.OpenAi.requestBody(ask(), openAiTarget)).jsonObject
        val content = body["messages"]!!.jsonArray.last().jsonObject["content"]

        assertNotNull(content!!.jsonArray)
    }

    @Test
    fun `a request with neither picture nor sound keeps the plain string content`() {
        val request = AiRequest(modelRef = TEST_MODEL_REF, prompt = "hi", maxOutputTokens = 100)
        val body = json.parseToJsonElement(OpenAiProtocol.OpenAi.requestBody(request, openAiTarget)).jsonObject

        assertEquals("hi", body["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the chat endpoint refuses a format only the transcription endpoint takes`() {
        val problem = OpenAiProtocol.OpenAi.audioProblem(ask(mediaType = "audio/mp4"), openAiTarget)

        assertNotNull(problem)
        assertTrue(problem!!.contains("audio/mp4"))
    }

    // ---- which wire ------------------------------------------------------------

    @Test
    fun `openai has a transcription endpoint and gemini does not`() {
        assertEquals(
            "https://api.openai.com/v1/audio/transcriptions",
            OpenAiProtocol.OpenAi.transcriptionEndpoint(openAiTarget),
        )
        assertEquals(
            "http://192.168.1.10:8000/v1/audio/transcriptions",
            OpenAiProtocol.SelfHosted.transcriptionEndpoint(selfHostedTarget),
        )
        assertNull(GeminiProtocol.transcriptionEndpoint(geminiTarget))
        assertNull(AnthropicProtocol.transcriptionEndpoint(claudeTarget))
    }

    /**
     * OpenRouter does publish a transcription endpoint, and it takes a JSON body of its
     * own rather than OpenAI's multipart upload. Claiming it here would be a 400 dressed
     * up as a transcription failure; it has audio-capable chat models instead.
     */
    @Test
    fun `openrouter is not offered the transcription wire`() {
        assertNull(OpenAiProtocol.OpenRouter.transcriptionEndpoint(openRouterTarget))
    }

    @Test
    fun `no question plus a transcription endpoint takes the transcription wire`() {
        val wire = audioWireFor(ask(prompt = ""), OpenAiProtocol.OpenAi, openAiTarget)

        assertTrue(wire is AudioWire.Transcription)
    }

    @Test
    fun `a question takes the chat wire even where a transcription endpoint exists`() {
        assertEquals(AudioWire.Chat, audioWireFor(ask(), OpenAiProtocol.OpenAi, openAiTarget))
    }

    /**
     * The case that forced one function rather than two checks. `audio/mp4` is refused by
     * the chat endpoint and accepted by the transcription one, so asking "is this
     * sendable?" before choosing the wire would refuse exactly the file the second wire
     * exists for.
     */
    @Test
    fun `an mp4 with no question reaches the transcription wire rather than being refused`() {
        val wire = audioWireFor(ask(prompt = "", mediaType = "audio/mp4"), OpenAiProtocol.OpenAi, openAiTarget)

        assertTrue(wire is AudioWire.Transcription)
    }

    @Test
    fun `an mp4 with a question is refused, because that can only go down the chat wire`() {
        val wire = audioWireFor(ask(mediaType = "audio/mp4"), OpenAiProtocol.OpenAi, openAiTarget)

        assertTrue(wire is AudioWire.Refused)
    }

    @Test
    fun `gemini always takes the chat wire, question or not`() {
        assertEquals(AudioWire.Chat, audioWireFor(ask(prompt = ""), GeminiProtocol, geminiTarget))
        assertEquals(AudioWire.Chat, audioWireFor(ask(), GeminiProtocol, geminiTarget))
    }

    @Test
    fun `claude is refused whichever way it is asked`() {
        assertTrue(audioWireFor(ask(prompt = ""), AnthropicProtocol, claudeTarget) is AudioWire.Refused)
        assertTrue(audioWireFor(ask(), AnthropicProtocol, claudeTarget) is AudioWire.Refused)
    }

    @Test
    fun `a request with no sound is unaffected`() {
        val request = AiRequest(modelRef = TEST_MODEL_REF, prompt = "", maxOutputTokens = 100)

        assertEquals(AudioWire.Chat, audioWireFor(request, OpenAiProtocol.OpenAi, openAiTarget))
    }

    // ---- reading a transcript --------------------------------------------------

    @Test
    fun `a transcript is read off the text field`() {
        val reply = OpenAiProtocol.OpenAi.readTranscription(200, """{"text":"hello there"}""")

        assertEquals("hello there", reply.text)
        assertEquals("", reply.error)
    }

    @Test
    fun `a refused transcription prefers the server's own sentence`() {
        val reply = OpenAiProtocol.OpenAi.readTranscription(
            status = 400,
            body = """{"error":{"message":"The model whisper-9 does not exist"}}""",
        )

        assertTrue(reply.error.contains("whisper-9"))
        assertEquals("", reply.text)
    }

    /**
     * An empty transcript is what a silent recording produces, which happens for real —
     * some OEM builds record zeroes rather than failing when the grant is missing. Saying
     * "the model returned an empty answer" would send somebody to the wrong end of it.
     */
    @Test
    fun `an empty transcript names silence rather than the model`() {
        val reply = OpenAiProtocol.OpenAi.readTranscription(200, """{"text":""}""")

        assertTrue(reply.error.contains("silent"))
    }

    /** Unreachable while the endpoint is null, and honest about being so. */
    @Test
    fun `a protocol with no transcription endpoint says that rather than misparsing`() {
        val reply = GeminiProtocol.readTranscription(200, """{"text":"hello"}""")

        assertTrue(reply.error.contains("no transcription endpoint"))
        assertEquals("", reply.text)
    }

    @Test
    fun `the model id travels as a form field`() {
        assertEquals(
            mapOf("model" to "whisper-1"),
            OpenAiProtocol.SelfHosted.transcriptionFields(selfHostedTarget),
        )
    }
}
