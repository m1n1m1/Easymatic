package io.github.m1n1m1.easymatic.data.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.service.CaptureOutcome
import io.github.m1n1m1.easymatic.core.service.CaptureRequest
import io.github.m1n1m1.easymatic.core.service.FileResult
import io.github.m1n1m1.easymatic.core.service.Microphone
import io.github.m1n1m1.easymatic.core.service.RecordingOutcome
import io.github.m1n1m1.easymatic.core.service.RecordingQuality
import io.github.m1n1m1.easymatic.core.service.RecordingRecord
import io.github.m1n1m1.easymatic.core.service.RecordingRequest
import io.github.m1n1m1.easymatic.core.service.WhenExists
import io.github.m1n1m1.easymatic.core.trigger.TriggerBus
import io.github.m1n1m1.easymatic.core.trigger.TriggerEvent
import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.data.service.ServiceForeground
import io.github.m1n1m1.easymatic.engine.trigger.RecordingEventCodec
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [Microphone] over `MediaRecorder`, writing through the file layer.
 *
 * ### Why it records to a temporary file and then moves it
 *
 * `MediaRecorder` writes to a file descriptor it owns for the length of the recording, and a
 * SAF document's descriptor is not something to hold open for five minutes across a provider
 * that may be a cloud client. So the recording lands in a private file first, and
 * `RoutingFiles.place` then puts it where the node asked — which is the one piece of code
 * that already knows how to create the folders above a path, how to route between the app's
 * storage and a granted folder, and what `KEEP_BOTH` should call the second file.
 * Re-implementing any of that here is how two answers to "where did it go?" start to
 * disagree.
 *
 * **The private file is beside the app's file storage rather than inside it**, and that is
 * load-bearing in both directions. `AppFileStore` is rooted at `macrofiles/`, so a partly
 * written recording under `filesDir` has no name in the graph's address space at all — no
 * `action.file_list` shows it and no `action.file_delete` can reach it — and equally, a path
 * pointing at it means something else entirely to every node. That is why the placement takes
 * a real [File] rather than a path.
 *
 * The move happens **after** the recorder has been released, so nothing copies a file that is
 * still being written.
 *
 * ### Why there is exactly one session
 *
 * There is one microphone. A second [start] answers with a sentence rather than taking the
 * hardware from the first, and the [Mutex] is what makes "is one running?" and "begin one" a
 * single decision — without it two macros firing together both see nothing running, and the
 * second leaks a recorder that nothing will ever stop.
 *
 * ### Why every ending goes through one function
 *
 * A recording ends by its own limit, by [stop], or because something else stopped it, and all
 * of those have to release the recorder, work out the length, move the file and tell
 * `trigger.recording_saved`. Three copies of that is three chances to leak the microphone.
 *
 * ### What it does about the foreground type
 *
 * From API 30 a foreground service reaches the microphone only while its declared type says
 * so, and the failure when it does not is the worst kind available: the recording succeeds
 * and contains silence. [ServiceForeground.withMicrophone] holds the claim for exactly as
 * long as the recorder does — which for [start] spans two separate calls, so the claim is
 * kept by a coroutine that lives for the session rather than by a block around either one.
 */
@Suppress("TooManyFunctions") // The four facade members plus the one place a recording
// ends and the helpers only it needs; splitting would put the ending somewhere else.
class AndroidMicrophone(
    context: Context,
    private val place: suspend (File, String, WhenExists) -> FileResult,
    private val scope: CoroutineScope,
) : Microphone {

    private val appContext = context.applicationContext

    private val lock = Mutex()

    @Volatile
    private var active: Session? = null

    /**
     * Whether [capture] is holding the microphone.
     *
     * A flag beside [active] rather than a second kind of [Session], because a capture
     * has no recorder to stop, no temp file to move and nothing to announce — every
     * field a session carries and every step [finish] takes. Making it one type would
     * put three `if` branches inside the one function whose whole justification is that
     * a recording ends in exactly one place.
     *
     * Guarded by the same [lock], which is the part that matters: there is one
     * microphone, so "is anything running?" and "claim it" have to be one decision
     * across both kinds or two macros firing together both see nothing running.
     */
    @Volatile
    private var capturing = false

    /** Completed to end the running capture early. */
    private var captureStop: CompletableDeferred<Unit>? = null

    /** The reading job, awaited by [endCapture] so the last buffer is not lost. */
    private var captureJob: Job? = null

    /**
     * A finished clip nobody has collected yet.
     *
     * Exists because a capture can end **without** anybody asking it to: its own limit
     * runs out. Discarding the clip then would mean a macro that listened for the full
     * minute got silence from its stop node, which reads as the microphone having failed.
     */
    private var pendingCapture: CaptureOutcome? = null

    /**
     * True while either kind of session holds the microphone.
     *
     * **Both, not just [active]**, and the reason is what this member is for: it is what
     * `value.recording` reads, and a "no" while the system's own recording indicator is
     * lit is precisely the answer that member exists to prevent. It is also what stops
     * `action.record_start` reaching for hardware an `action.ai_listen` already has.
     */
    override fun isRecording(): Boolean = active != null || capturing

    override suspend fun record(request: RecordingRequest): RecordingOutcome {
        val begun = lock.withLock { begin(request) }
        val session = begun.session ?: return RecordingOutcome(error = begun.problem)
        try {
            delay(request.seconds.toLong() * MILLIS_PER_SECOND)
        } catch (cancelled: CancellationException) {
            // This session has no limit job — the wait above *is* its length — so a
            // disarmed macro or a stopped run would otherwise leave the microphone open
            // with nothing anywhere left to close it. [NonCancellable] because the ending
            // suspends, and a cancelled scope would abandon it half-way through.
            withContext(NonCancellable) {
                lock.withLock { if (active === session) finish(session) }
            }
            throw cancelled
        }
        return lock.withLock {
            // Something else may have stopped it while we waited — another macro's
            // `action.record_stop`, or a re-arm taking the engine down. The file is not
            // lost: it went to `trigger.recording_saved` on its way out. There is simply no
            // receipt to hand back here, and saying so beats reporting a failure.
            if (active !== session) {
                RecordingOutcome(error = "The recording was stopped by something else")
            } else {
                finish(session)
            }
        }
    }

    override suspend fun start(request: RecordingRequest): String = lock.withLock {
        val begun = begin(request)
        val session = begun.session ?: return@withLock begun.problem
        session.limit = scope.launch {
            delay(request.seconds.toLong() * MILLIS_PER_SECOND)
            lock.withLock { if (active === session) finish(session) }
        }
        ""
    }

    override suspend fun stop(): RecordingOutcome = lock.withLock {
        val session = active ?: return@withLock RecordingOutcome(error = "Nothing is recording")
        finish(session)
    }

    /**
     * A clip held in memory, taking the microphone for as long as it runs.
     *
     * **The claim is taken under [lock] and released in a `finally`, but the read itself
     * is not held under it**, and that split is deliberate. Holding the mutex for the
     * whole two minutes would make `stop()` — and `isRecording()`'s callers — wait on a
     * clip they have nothing to do with; the flag is what other entry points actually
     * need to see, so the flag is what the lock protects.
     *
     * **Nothing reaches `TriggerBus`.** `trigger.recording_saved` says "here is a file",
     * and there is no file: firing it with a path that resolves to nothing would break
     * every macro downstream of that trigger the first time somebody used this node.
     */
    override suspend fun capture(request: CaptureRequest): CaptureOutcome {
        val refusal = lock.withLock {
            when {
                active != null -> "A recording is already running"
                capturing -> "Already listening"
                else -> {
                    capturing = true
                    null
                }
            }
        }
        if (refusal != null) return CaptureOutcome(error = refusal)
        return try {
            AudioCapture.record(appContext, request)
        } finally {
            // NonCancellable so a stopped run still gives the microphone back; without it
            // the flag sticks and every later listen answers "already listening".
            withContext(NonCancellable) { lock.withLock { capturing = false } }
        }
    }

    /**
     * Opens a capture that runs until [endCapture] or until its own limit.
     *
     * **The reading runs on [scope], not on the caller**, which is the whole difference
     * from [capture] and the reason `action.ai_listen_start` can pulse `out` at once: the
     * node that starts a capture returns long before the clip does, so a job tied to that
     * node's coroutine would be cancelled the moment it finished.
     *
     * The clip is **kept when the limit ends it** rather than thrown away, in
     * [pendingCapture], so a macro that listened for its full sixty seconds still has
     * something for its stop node to send. The microphone is released either way, which
     * is what stops a forgotten capture holding the hardware.
     */
    @Suppress("ReturnCount") // Busy, unstartable and begun are three answers a node reports differently.
    override suspend fun beginCapture(request: CaptureRequest): String {
        val refusal = lock.withLock {
            when {
                active != null -> "A recording is already running"
                capturing -> "Already listening"
                else -> {
                    capturing = true
                    pendingCapture = null
                    captureStop = CompletableDeferred()
                    null
                }
            }
        }
        if (refusal != null) return refusal
        val signal = captureStop ?: return "Could not start listening"
        captureJob = scope.launch {
            val outcome = AudioCapture.record(appContext, request, signal)
            withContext(NonCancellable) {
                lock.withLock {
                    pendingCapture = outcome
                    capturing = false
                }
            }
        }
        return ""
    }

    /**
     * Ends the capture and answers with the clip, or collects one the limit already ended.
     *
     * Awaits the reading job rather than reading [pendingCapture] straight away, because
     * the loop checks the stop signal once per buffer: returning immediately would hand
     * back a clip missing its last tenth of a second, or — if the stop arrived first —
     * nothing at all.
     */
    override suspend fun endCapture(): CaptureOutcome {
        val job = lock.withLock {
            captureStop?.complete(Unit)
            captureJob
        }
        job?.join()
        return lock.withLock {
            val outcome = pendingCapture
            pendingCapture = null
            captureStop = null
            captureJob = null
            outcome ?: CaptureOutcome(error = "Nothing is listening")
        }
    }

    /**
     * Opens a recorder and makes it the running session, or explains why not.
     *
     * Called under [lock] by all three entry points, which is what makes the check and the
     * claim one decision.
     */
    @Suppress("ReturnCount") // Already recording, no microphone, and begun are three
    // distinct answers, and collapsing any two loses the sentence the node reports.
    private suspend fun begin(request: RecordingRequest): Begun {
        if (active != null) return Begun(problem = "A recording is already running")
        val folder = File(appContext.filesDir, TEMP_FOLDER)
        withContext(Dispatchers.IO) { folder.mkdirs() }
        val temp = File(folder, "part-" + System.currentTimeMillis() + "." + EXTENSION)
        val recorder = withContext(Dispatchers.IO) { open(temp, request.quality) }
            ?: return Begun(problem = "The microphone is not available — another app may be using it")
        val session = Session(
            recorder = recorder,
            temp = temp,
            target = targetOf(request),
            whenExists = request.whenExists,
            startedAtMs = System.currentTimeMillis(),
        )
        // Held until the session ends, which is the point: `start` returns long before the
        // recording does, so a scoped block around either call would drop the claim while
        // the microphone is still open. This job parks on the latch and releases as it
        // unwinds.
        scope.launch { ServiceForeground.withMicrophone { session.ended.await() } }
        active = session
        return Begun(session = session)
    }

    /**
     * Ends [session] and answers with the file — the one place a recording stops.
     *
     * Always called under [lock], and always clears [active] first, so a failure anywhere
     * below leaves the microphone free rather than permanently "already recording".
     */
    @Suppress("ReturnCount") // Too short to save, nowhere to put it, and saved are three
    // distinct outcomes, and each carries a different sentence for the console.
    private suspend fun finish(session: Session): RecordingOutcome {
        active = null
        session.limit?.cancel()
        val elapsedMs = System.currentTimeMillis() - session.startedAtMs
        val stopped = withContext(Dispatchers.IO) {
            // `stop()` throws when the recorder never got any data — a recording ended
            // within a few dozen milliseconds of starting — and leaves a file that will not
            // play. Releasing it is not conditional on that.
            val ok = runCatching { session.recorder.stop() }.isSuccess
            runCatching { session.recorder.release() }
            ok
        }
        session.ended.complete(Unit)
        if (!stopped) {
            withContext(Dispatchers.IO) { session.temp.delete() }
            return RecordingOutcome(error = "The recording was too short to save")
        }
        val sizeBytes = withContext(Dispatchers.IO) { session.temp.length() }
        val durationMs = durationOf(session.temp) ?: elapsedMs
        val moved = place(session.temp, session.target, session.whenExists)
        withContext(Dispatchers.IO) { session.temp.delete() }
        if (moved.error.isNotBlank() || !moved.changed) {
            val why = moved.error.ifBlank {
                session.target + " was already there, so the recording was not saved"
            }
            return RecordingOutcome(error = why)
        }
        // The requested path is not the placed one when a collision renamed the file, and
        // the path is what every node downstream addresses it by.
        val path = placed(session.target, moved.name)
        announce(path, moved.name, durationMs, sizeBytes)
        return RecordingOutcome(
            changed = true,
            path = path,
            name = moved.name,
            durationMs = durationMs,
            sizeBytes = sizeBytes,
        )
    }

    /** Tells `trigger.recording_saved`, whichever of the endings got us here. */
    private fun announce(path: String, name: String, durationMs: Long, sizeBytes: Long) {
        TriggerBus.emit(
            TriggerEvent(
                source = TriggerSource.RECORDING,
                triggerNodeId = NodeId.BROADCAST,
                payload = RecordingEventCodec.encode(
                    RecordingRecord(
                        path = path,
                        name = name,
                        folder = path.substringBeforeLast('/', ""),
                        mimeType = MIME_TYPE,
                        durationMs = durationMs,
                        sizeBytes = sizeBytes,
                        recordedAtEpochMs = System.currentTimeMillis(),
                    ),
                ),
            ),
        )
    }

    /**
     * A prepared, started recorder, or null.
     *
     * AAC in an MPEG-4 container: the one combination every Android phone can both write and
     * play, and the one every mail client and messenger accepts as an attachment.
     */
    @Suppress("TooGenericExceptionCaught") // A refused microphone throws several unrelated types.
    private fun open(temp: File, quality: RecordingQuality): MediaRecorder? = try {
        val high = quality == RecordingQuality.HIGH
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(appContext)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder.setAudioChannels(if (high) HIGH_CHANNELS else VOICE_CHANNELS)
        recorder.setAudioSamplingRate(if (high) HIGH_RATE else VOICE_RATE)
        recorder.setAudioEncodingBitRate(if (high) HIGH_BITS else VOICE_BITS)
        recorder.setOutputFile(temp.absolutePath)
        recorder.prepare()
        recorder.start()
        recorder
    } catch (_: Exception) {
        null
    }

    /**
     * The recording's real length, or null when the file will not say.
     *
     * Asked of the file rather than measured with the clock because the two genuinely
     * differ: the encoder drops the tail it had not finished, and a phone that throttled
     * mid-recording produces less audio than wall time. The clock is the fallback, which is
     * the honest ordering — a length that is nearly right beats no length at all.
     */
    @Suppress("TooGenericExceptionCaught") // A malformed file throws from several places.
    private suspend fun durationOf(file: File): Long? = withContext(Dispatchers.IO) {
        try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally {
                retriever.release()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Where the recording is being asked to go.
     *
     * A blank folder is the app's own storage, which needs no grant of any kind — the
     * commonest case, and the one that has to work on a phone where nothing has been granted.
     */
    private fun targetOf(request: RecordingRequest): String {
        val name = request.name.ifBlank { defaultName() }
        val named = if (name.contains('.')) name else name + "." + EXTENSION
        val folder = request.toFolder.trim().trimEnd('/').ifBlank { DEFAULT_FOLDER }
        return folder + "/" + named
    }

    private fun defaultName(): String =
        "recording-" + SimpleDateFormat(NAME_STAMP, Locale.US).format(Date())

    /** [target] with its last segment replaced by the name the file layer actually used. */
    private fun placed(target: String, name: String): String = when {
        name.isBlank() || target.endsWith("/" + name) -> target
        else -> target.substringBeforeLast('/', "")
            .let { parent -> if (parent.isBlank()) name else parent + "/" + name }
    }

    /** What [begin] answers: a running session, or the sentence explaining why not. */
    private class Begun(val session: Session? = null, val problem: String = "")

    private class Session(
        val recorder: MediaRecorder,
        val temp: File,
        val target: String,
        val whenExists: WhenExists,
        val startedAtMs: Long,
    ) {
        /** Completed as the recorder is released, which is what drops the foreground claim. */
        val ended = CompletableDeferred<Unit>()

        var limit: Job? = null
    }

    private companion object {
        const val TEMP_FOLDER = "recordings-part"
        const val DEFAULT_FOLDER = "Recordings"
        const val EXTENSION = "m4a"
        const val MIME_TYPE = "audio/mp4"
        const val NAME_STAMP = "yyyyMMdd-HHmmss"
        const val MILLIS_PER_SECOND = 1000L
        const val VOICE_CHANNELS = 1
        const val HIGH_CHANNELS = 2
        const val VOICE_RATE = 16_000
        const val HIGH_RATE = 44_100
        const val VOICE_BITS = 32_000
        const val HIGH_BITS = 192_000
    }
}
