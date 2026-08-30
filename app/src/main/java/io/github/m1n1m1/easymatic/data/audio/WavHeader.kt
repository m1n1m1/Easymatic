package io.github.m1n1m1.easymatic.data.audio

import java.io.ByteArrayOutputStream

/**
 * The forty-four bytes that turn raw PCM into a file a model will accept.
 *
 * **Written by hand because Android has no encoder for this**, which sounds like an
 * omission and is not: `MediaRecorder` and `MediaCodec` both exist to *compress*, and
 * WAV is the format defined by not compressing. There is nothing to call.
 *
 * It is a **pure function over four numbers**, in its own file, for the reason every
 * protocol in `data/ai/` is: the interesting half is the byte layout, and a byte layout
 * is exactly the thing worth pinning with a JVM test. The `AudioRecord` half beside it
 * needs a device and a microphone and is untestable here, the same way `MediaRecorder`
 * is in `AndroidMicrophone` — so the two are separated along the line where testing
 * stops being possible rather than along a tidier one.
 *
 * Everything is **little-endian**, which is the one thing about RIFF that is easy to
 * get wrong and impossible to notice: a big-endian size field yields a file that opens,
 * plays as a fraction of a second, and is rejected by a model as malformed with no
 * indication which of the two ends produced it.
 */
internal object WavHeader {

    /** `RIFF` + the four-byte size + `WAVE`, then the two chunks. */
    const val BYTES: Int = 44

    /**
     * A canonical 44-byte PCM header for [dataBytes] of samples.
     *
     * [dataBytes] is the length of the payload that follows, so this can only be
     * written once the whole clip is in hand — which is why the capture accumulates
     * into memory and prepends afterwards rather than streaming to a file. That is
     * affordable here precisely because [io.github.m1n1m1.easymatic.core.service.CaptureLimits.MAX_SECONDS]
     * bounds the clip; it would not be for `action.record_audio`, which is why that one
     * still goes through `MediaRecorder` and a file descriptor.
     */
    fun of(
        dataBytes: Int,
        sampleRateHz: Int,
        channels: Int = 1,
        bitsPerSample: Int = 16,
    ): ByteArray {
        val byteRate = sampleRateHz * channels * bitsPerSample / BITS_PER_BYTE
        val blockAlign = channels * bitsPerSample / BITS_PER_BYTE
        val out = ByteArrayOutputStream(BYTES)
        out.ascii("RIFF")
        // The whole file minus the eight bytes of `RIFF` and this field itself.
        out.leInt(dataBytes + BYTES - RIFF_PREAMBLE_BYTES)
        out.ascii("WAVE")
        out.ascii("fmt ")
        out.leInt(PCM_CHUNK_BYTES)
        out.leShort(PCM_FORMAT)
        out.leShort(channels)
        out.leInt(sampleRateHz)
        out.leInt(byteRate)
        out.leShort(blockAlign)
        out.leShort(bitsPerSample)
        out.ascii("data")
        out.leInt(dataBytes)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.ascii(text: String) {
        text.forEach { write(it.code) }
    }

    private fun ByteArrayOutputStream.leInt(value: Int) {
        write(value and BYTE_MASK)
        write((value shr ONE_BYTE) and BYTE_MASK)
        write((value shr TWO_BYTES) and BYTE_MASK)
        write((value shr THREE_BYTES) and BYTE_MASK)
    }

    private fun ByteArrayOutputStream.leShort(value: Int) {
        write(value and BYTE_MASK)
        write((value shr ONE_BYTE) and BYTE_MASK)
    }

    private const val BITS_PER_BYTE = 8
    private const val BYTE_MASK = 0xFF

    /** How far to shift to reach each successive byte, low to high. */
    private const val ONE_BYTE = 8
    private const val TWO_BYTES = 16
    private const val THREE_BYTES = 24

    /** `RIFF` plus the size field, which the size field does not count. */
    private const val RIFF_PREAMBLE_BYTES = 8

    /** A `fmt ` chunk describing uncompressed PCM is always sixteen bytes. */
    private const val PCM_CHUNK_BYTES = 16

    /** WAVE_FORMAT_PCM. */
    private const val PCM_FORMAT = 1
}
