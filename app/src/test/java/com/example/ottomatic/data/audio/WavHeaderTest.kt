package com.example.ottomatic.data.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The forty-four bytes that turn raw PCM into a file a model will accept.
 *
 * **Worth pinning because every way of getting this wrong produces a file rather than an
 * error.** A big-endian size field, an off-by-eight in the RIFF length, a byte rate
 * computed from the wrong channel count: each yields something that opens, plays as a
 * fraction of a second or as noise, and comes back from a provider as "could not decode
 * the audio" — a sentence about the file that gives no hint which of the four numbers is
 * to blame. The `AudioRecord` half beside this needs a device and a microphone and stays
 * untested here, the same way `MediaRecorder` does in `AndroidMicrophone`; the split is
 * along the line where testing stops being possible.
 */
class WavHeaderTest {

    private fun ascii(header: ByteArray, at: Int, length: Int = 4) =
        String(header, at, length, Charsets.US_ASCII)

    private fun leInt(header: ByteArray, at: Int): Int =
        (header[at].toInt() and 0xFF) or
            ((header[at + 1].toInt() and 0xFF) shl 8) or
            ((header[at + 2].toInt() and 0xFF) shl 16) or
            ((header[at + 3].toInt() and 0xFF) shl 24)

    private fun leShort(header: ByteArray, at: Int): Int =
        (header[at].toInt() and 0xFF) or ((header[at + 1].toInt() and 0xFF) shl 8)

    @Test
    fun `a canonical PCM header is forty-four bytes`() {
        assertEquals(WavHeader.BYTES, WavHeader.of(dataBytes = 32_000, sampleRateHz = 16_000).size)
    }

    @Test
    fun `the four chunk markers are where a reader looks for them`() {
        val header = WavHeader.of(dataBytes = 32_000, sampleRateHz = 16_000)

        assertEquals("RIFF", ascii(header, 0))
        assertEquals("WAVE", ascii(header, 8))
        assertEquals("fmt ", ascii(header, 12))
        assertEquals("data", ascii(header, 36))
    }

    /**
     * The eight-byte offset is the classic mistake: the RIFF size counts everything after
     * itself, not the whole file. Too large by eight and a strict decoder reads past the
     * end; too small and it drops the last samples.
     */
    @Test
    fun `the RIFF size excludes the marker and its own field`() {
        val header = WavHeader.of(dataBytes = 32_000, sampleRateHz = 16_000)

        assertEquals(32_000 + WavHeader.BYTES - 8, leInt(header, 4))
        assertEquals(32_000, leInt(header, 40))
    }

    @Test
    fun `the format chunk describes uncompressed mono PCM`() {
        val header = WavHeader.of(dataBytes = 32_000, sampleRateHz = 16_000)

        assertEquals(16, leInt(header, 16))
        assertEquals(1, leShort(header, 20))
        assertEquals(1, leShort(header, 22))
        assertEquals(16, leShort(header, 34))
    }

    /**
     * The two derived numbers, which are what a decoder uses to work out how long the
     * clip is. A wrong byte rate plays at the wrong speed rather than failing.
     */
    @Test
    fun `the byte rate and block align follow from the format`() {
        val header = WavHeader.of(dataBytes = 32_000, sampleRateHz = 16_000)

        assertEquals(16_000, leInt(header, 24))
        assertEquals(16_000 * 2, leInt(header, 28))
        assertEquals(2, leShort(header, 32))
    }

    @Test
    fun `stereo doubles the rate and the alignment`() {
        val header = WavHeader.of(dataBytes = 64_000, sampleRateHz = 44_100, channels = 2)

        assertEquals(2, leShort(header, 22))
        assertEquals(44_100 * 2 * 2, leInt(header, 28))
        assertEquals(4, leShort(header, 32))
    }

    /** Little-endian throughout, which is the one thing about RIFF that is silently fatal. */
    @Test
    fun `multi-byte fields are little-endian`() {
        val header = WavHeader.of(dataBytes = 0x01020304, sampleRateHz = 16_000)

        assertEquals(0x04, header[40].toInt() and 0xFF)
        assertEquals(0x03, header[41].toInt() and 0xFF)
        assertEquals(0x02, header[42].toInt() and 0xFF)
        assertEquals(0x01, header[43].toInt() and 0xFF)
    }
}
