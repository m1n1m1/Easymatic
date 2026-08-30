package io.github.m1n1m1.easymatic.data.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import androidx.core.content.ContextCompat
import io.github.m1n1m1.easymatic.core.service.AudioLimits
import io.github.m1n1m1.easymatic.core.service.CaptureLimits
import io.github.m1n1m1.easymatic.core.service.CaptureOutcome
import io.github.m1n1m1.easymatic.core.service.CaptureRequest
import io.github.m1n1m1.easymatic.data.service.ServiceForeground
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * One clip of raw microphone audio, held in memory and answered as WAV.
 *
 * **`AudioRecord` rather than `MediaRecorder`, which is the opposite call from
 * [AndroidMicrophone] and is forced by the destination rather than by taste.**
 * `MediaRecorder` compresses, needs a file descriptor to write into, and on this phone
 * produces MPEG-4/AAC — which is exactly right for a recording somebody keeps and is
 * refused by two of the three providers that can hear at all. What a model wants is
 * uncompressed mono PCM, and `AudioRecord` is the only API that hands that over. The
 * cost is that the WAV container has to be written by hand ([WavHeader]); the benefit
 * is that the samples are in this process, so nothing is ever written to storage and
 * there is nothing to clean up if the macro is stopped mid-clip.
 *
 * **Silence detection is RMS over each buffer, and it is genuinely crude.** There is no
 * platform voice-activity detector behind a raw read — that lives inside
 * `SpeechRecognizer`, which is what `action.listen` uses and is the reason that node
 * can hand end-of-speech detection to the phone. Here the choice is between a threshold
 * and nothing, and nothing means every clip runs the full length with the microphone
 * indicator lit. So: a threshold, stated as such, with `0` meaning "do not stop early"
 * rather than "let the phone decide".
 *
 * **Two bounds stop the loop, and both are needed.** The clock bound is the courtesy
 * one — an open microphone is visible to the user the whole time. The byte bound is the
 * real one: this accumulates into the heap of the process holding every armed macro, so
 * a clock bound alone would be at the mercy of whatever sample rate a future edit chose.
 *
 * Nothing throws. Every failure is a worded [CaptureOutcome], on the [Microphone]
 * facade's contract: a microphone is unusually easy to be refused, and none of the ways
 * is a reason for a macro to stop.
 */
internal object AudioCapture {

    /**
     * Records for at most [CaptureRequest.maxSeconds] and answers with the WAV.
     *
     * Wrapped in [ServiceForeground.withMicrophone] for the reason that function exists:
     * from API 30 a foreground service reaches the microphone only while its declared
     * type says so, and the failure when it does not is the worst kind available — the
     * read succeeds and the samples are all zero. The claim lives here rather than in
     * the node because `engine` may not reach `data`.
     */
    suspend fun record(
        context: Context,
        request: CaptureRequest,
        stopSignal: CompletableDeferred<Unit>? = null,
    ): CaptureOutcome {
        if (!granted(context)) {
            return CaptureOutcome(error = "Easymatic does not have permission to use the microphone")
        }
        return ServiceForeground.withMicrophone { read(request, stopSignal) }
    }

    private fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    @Suppress("ReturnCount") // A refused recorder, an empty clip and a finished one each say their own thing.
    @SuppressLint("MissingPermission") // Checked in `record` above, which is the only caller.
    private suspend fun read(
        request: CaptureRequest,
        stopSignal: CompletableDeferred<Unit>?,
    ): CaptureOutcome = withContext(Dispatchers.IO) {
        val seconds = request.maxSeconds.coerceIn(1, CaptureLimits.MAX_SECONDS)
        val rate = CaptureLimits.SAMPLE_RATE_HZ
        val minimum = AudioRecord.getMinBufferSize(rate, CHANNEL, ENCODING)
        if (minimum <= 0) {
            return@withContext CaptureOutcome(error = "This phone cannot record at ${rate / 1000} kHz")
        }
        val bufferBytes = maxOf(minimum, rate * BYTES_PER_SAMPLE / BUFFERS_PER_SECOND)
        val recorder = runCatching {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, CHANNEL, ENCODING, bufferBytes * RECORDER_BUFFERS)
        }.getOrNull()
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder?.release()
            return@withContext CaptureOutcome(error = "The microphone is busy or unavailable")
        }
        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return@withContext CaptureOutcome(error = "The microphone did not start — another app may be using it")
            }
            collect(recorder, seconds, request.silenceSeconds, bufferBytes, rate, stopSignal)
        } catch (e: IllegalStateException) {
            CaptureOutcome(error = e.message.orEmpty().ifBlank { "The microphone could not be started" })
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    /**
     * The read loop, which ends on the clock, on the byte cap, or on quiet.
     *
     * `ensureActive` between buffers rather than only at the top, so Stop Macro ends a
     * two-minute clip when it is pressed rather than two minutes later — the same rule
     * `copyStream` follows in the file layer.
     */
    @Suppress("LongParameterList") // Each argument is one way the same loop can end.
    private suspend fun collect(
        recorder: AudioRecord,
        seconds: Int,
        silenceSeconds: Int,
        bufferBytes: Int,
        rate: Int,
        stopSignal: CompletableDeferred<Unit>?,
    ): CaptureOutcome {
        val byteCap = minOf(seconds * rate * BYTES_PER_SAMPLE, AudioLimits.MAX_MODEL_BYTES - WavHeader.BYTES)
        val collected = ByteArrayOutputStream(minOf(byteCap, INITIAL_BYTES))
        val buffer = ByteArray(bufferBytes)
        var quietBytes = 0
        val quietCap = if (silenceSeconds > 0) silenceSeconds * rate * BYTES_PER_SAMPLE else Int.MAX_VALUE
        var heardAnything = false
        // A flag rather than two `break`s: the loop has three ways to end — the byte cap,
        // a dead recorder and enough quiet — and reading them all off the `while` is what
        // keeps "when does this stop?" answerable in one place.
        var finished = false

        while (!finished && collected.size() < byteCap) {
            coroutineContext.ensureActive()
            // Checked once per buffer rather than awaited, so a stop lands within a tenth
            // of a second and the loop still owns when the recorder is released.
            val stopped = stopSignal?.isCompleted == true
            val read = if (stopped) 0 else recorder.read(buffer, 0, minOf(buffer.size, byteCap - collected.size()))
            if (read <= 0) {
                finished = true
            } else {
                collected.write(buffer, 0, read)
                val level = rms(buffer, read)
                if (level >= SPEECH_THRESHOLD) {
                    heardAnything = true
                    quietBytes = 0
                } else if (heardAnything) {
                    // Quiet only counts once something has been said. Otherwise a clip
                    // that starts before the speaker does ends before they get to it.
                    quietBytes += read
                    finished = quietBytes >= quietCap
                }
            }
        }

        val samples = collected.toByteArray()
        if (samples.isEmpty()) return CaptureOutcome(error = "The microphone returned nothing")
        val wav = WavHeader.of(samples.size, rate) + samples
        return CaptureOutcome(
            base64 = Base64.encodeToString(wav, Base64.NO_WRAP),
            mediaType = "audio/wav",
            durationMs = samples.size.toLong() * MILLIS_PER_SECOND / (rate * BYTES_PER_SAMPLE),
            heard = heardAnything,
        )
    }

    /**
     * How loud this buffer is, as a fraction of full scale.
     *
     * Root-mean-square rather than a peak, because a peak is one sample and one sample
     * is a click, a door, or the recorder settling — none of which is somebody talking.
     */
    private fun rms(buffer: ByteArray, length: Int): Double {
        var sum = 0.0
        var i = 0
        while (i + 1 < length) {
            // Little-endian signed 16-bit, which is what ENCODING_PCM_16BIT delivers.
            val sample = ((buffer[i + 1].toInt() shl BITS_PER_BYTE) or (buffer[i].toInt() and BYTE_MASK))
                .toShort().toInt()
            sum += sample.toDouble() * sample.toDouble()
            i += 2
        }
        val count = length / 2
        if (count == 0) return 0.0
        return sqrt(sum / count) / Short.MAX_VALUE
    }

    private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    private const val BYTES_PER_SAMPLE = 2
    private const val BITS_PER_BYTE = 8
    private const val BYTE_MASK = 0xFF
    private const val BUFFERS_PER_SECOND = 10
    private const val RECORDER_BUFFERS = 4
    private const val INITIAL_BYTES = 64 * 1024
    private const val MILLIS_PER_SECOND = 1000L

    /**
     * Where quiet stops and speech starts, as a fraction of full scale.
     *
     * Two per cent. Room tone on a phone sits an order of magnitude below it and
     * conversational speech an order above, so the exact value matters far less than
     * having one — and erring low is right, because the cost of a threshold set too
     * high is a clip that cuts the speaker off mid-sentence, where the cost of one set
     * too low is a clip that runs to its limit.
     */
    private const val SPEECH_THRESHOLD = 0.02
}
