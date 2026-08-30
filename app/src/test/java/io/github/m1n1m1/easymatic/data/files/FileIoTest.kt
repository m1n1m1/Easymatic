package io.github.m1n1m1.easymatic.data.files

import io.github.m1n1m1.easymatic.core.service.FileLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * What a file's name says it is, and what the byte reader will not read.
 *
 * **`mediaTypeOf` stopped being "the types every model accepts" when sound arrived**, and
 * that is the change worth pinning. While pictures were the only media the set could
 * double as a compatibility check, because every provider that sees images accepts the
 * same four. No such set exists for audio: Gemini refuses `audio/mp4`, OpenAI's chat wire
 * takes two formats and its transcription endpoint takes eight, and Claude takes none. So
 * this function answers what the *file* is, and `AiProtocol.audioProblem` answers whether
 * a given provider will take it — which means naming a type here that some provider
 * rejects is now correct rather than a bug.
 *
 * The success path of `readBoundedBase64` goes through `android.util.Base64` and so needs
 * a device; the refusal path returns before encoding, which is the half that matters most
 * here anyway.
 */
class FileIoTest {

    @Test
    fun `the four picture kinds still answer as they did`() {
        assertEquals("image/jpeg", mediaTypeOf("holiday.jpg"))
        assertEquals("image/jpeg", mediaTypeOf("holiday.jpeg"))
        assertEquals("image/png", mediaTypeOf("shot.png"))
        assertEquals("image/gif", mediaTypeOf("loop.gif"))
        assertEquals("image/webp", mediaTypeOf("small.webp"))
    }

    @Test
    fun `the sound kinds a model might be handed`() {
        assertEquals("audio/wav", mediaTypeOf("note.wav"))
        assertEquals("audio/mpeg", mediaTypeOf("song.mp3"))
        assertEquals("audio/aac", mediaTypeOf("clip.aac"))
        assertEquals("audio/ogg", mediaTypeOf("clip.ogg"))
        assertEquals("audio/ogg", mediaTypeOf("clip.oga"))
        assertEquals("audio/opus", mediaTypeOf("clip.opus"))
        assertEquals("audio/flac", mediaTypeOf("clip.flac"))
        assertEquals("audio/aiff", mediaTypeOf("clip.aiff"))
        assertEquals("audio/aiff", mediaTypeOf("clip.aif"))
    }

    /**
     * Named so it can be *refused* by name. This is what `action.record_audio` writes, and
     * Gemini will not take it — a blank answer here would have produced "that is not a
     * sound file" for the app's own recordings.
     */
    @Test
    fun `the app's own recording format is named rather than left blank`() {
        assertEquals("audio/mp4", mediaTypeOf("note.m4a"))
        assertEquals("audio/mp4", mediaTypeOf("note.mp4"))
    }

    @Test
    fun `an unknown or absent extension answers blank, which every caller treats as a refusal`() {
        assertEquals("", mediaTypeOf("notes.xyz"))
        assertEquals("", mediaTypeOf("README"))
        assertEquals("", mediaTypeOf(""))
    }

    @Test
    fun `the extension is read case-insensitively`() {
        assertEquals("audio/wav", mediaTypeOf("NOTE.WAV"))
        assertEquals("image/jpeg", mediaTypeOf("Holiday.JPG"))
    }

    // ---- the bound -------------------------------------------------------------

    @Test
    fun `a file over the caller's bound is refused rather than truncated`() {
        val bytes = ByteArray(2048)

        val read = readBoundedBase64(ByteArrayInputStream(bytes), "audio/wav", maxBytes = 1024)

        assertTrue(read.error.contains("too big"))
        assertEquals("", read.base64)
    }

    /** The number, not the name of the constant — a reader has to know what to change. */
    @Test
    fun `the refusal names the size in kilobytes`() {
        val read = readBoundedBase64(ByteArrayInputStream(ByteArray(4096)), "audio/wav", maxBytes = 2048)

        assertTrue(read.error.contains("2 KB"))
    }

    /**
     * The bound arrives from a caller now rather than from a constant, so it is clamped
     * here on the file layer's own account: whatever a node believes it is asking for, the
     * engine service is not going to be handed a hundred-megabyte array.
     */
    @Test
    fun `an absurd bound is clamped to the ceiling`() {
        val read = readBoundedBase64(
            ByteArrayInputStream(ByteArray(FileLimits.MAX_BYTES_CEILING + 1)),
            "audio/wav",
            maxBytes = Int.MAX_VALUE,
        )

        assertTrue(read.error.contains("too big"))
    }
}
