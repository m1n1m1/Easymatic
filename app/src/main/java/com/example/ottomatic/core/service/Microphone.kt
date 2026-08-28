package com.example.ottomatic.core.service

/**
 * Recording sound from the phone's microphone, for the three `action.record_*` nodes.
 *
 * Reached from `ExecutionContext` and nothing else, on `Files`' and `Images`' reasoning.
 * **Nothing here throws**: every member answers a result carrying an error string, so a node
 * reports what happened and pulses `out` rather than unwinding a run. A microphone is
 * unusually easy to be refused — another app may hold it, the grant may be gone, an OEM may
 * simply say no — and none of those is a reason for a macro to stop.
 *
 * **There is exactly one recording at a time, and that is a property of the phone rather
 * than a simplification.** There is one microphone; a second [start] while one is running
 * answers with a sentence instead of taking it away from the first, which is the only
 * behaviour that leaves the running macro working.
 *
 * **Where a recording goes is a path, not a media row** — the opposite call from [Images],
 * and the difference is what the thing *is*. A photo is an entry in a collection every
 * gallery on the phone reads, so MediaStore is the only place it can be. A recording is a
 * file somebody attaches to a mail or sends to a chat, and the six `action.file_*` nodes are
 * already the vocabulary for that; putting it in a media collection would mean a macro
 * could make one and then not be able to name it. So [RecordingRequest.toFolder] is read
 * through `RoutingFiles`, exactly like `action.file_write`'s, and a blank one means the
 * app's own storage — which needs no grant of any kind.
 *
 * **[isRecording] is the one member that is not suspending and cannot fail**, because it is
 * what `value.recording` reads: a flag this process already holds, so the pull side's
 * "cheap, repeatable, cannot fail" contract holds without qualification.
 */
interface Microphone {

    /**
     * Records for [RecordingRequest.seconds] and answers with the finished file.
     *
     * Suspends for the whole length, which is what `action.record_audio` wants: a macro that
     * says "record five seconds, then mail it" is written top to bottom, and a fork would
     * put the mailing on a branch for no reason the user asked for.
     */
    suspend fun record(request: RecordingRequest): RecordingOutcome

    /**
     * Begins a recording and returns at once, answering a problem or `""`.
     *
     * A bare string rather than a [RecordingOutcome] because there is no outcome yet — the
     * file does not exist and has no length. `action.record_start` logs whatever comes back
     * and pulses `out` either way; the finished recording arrives at `action.record_stop` or
     * at `trigger.recording_saved`.
     *
     * [RecordingRequest.seconds] is a limit rather than a length here, and it is not
     * optional: a recording nobody stops would hold the microphone until the process dies,
     * with the system's recording indicator lit the whole time.
     */
    suspend fun start(request: RecordingRequest): String

    /**
     * Ends the running recording and answers with the finished file.
     *
     * With nothing running this is [RecordingOutcome.changed] `false` and an error saying
     * so — which is a thing worth reporting rather than a silent no-op, because a macro
     * reaching a stop it did not start is usually a graph that ran in an order its author
     * did not expect.
     */
    suspend fun stop(): RecordingOutcome

    /**
     * Listens for a moment and answers with the sound itself, writing nothing anywhere.
     *
     * **The one member that produces no file, which is exactly why it is a member
     * rather than a [RecordingRequest] with a clever folder.** `action.ai_listen` wants
     * a clip that exists for the length of one network call and then does not exist —
     * it has no name anybody chose, nowhere anybody wants it, and nothing to clean up
     * afterwards. [RecordingRequest] cannot express that: it takes a folder and a name
     * because a recording is a thing somebody keeps. Routing a live listen through it
     * would leave a file behind on every run, and "delete it afterwards" is a promise
     * this facade would then have to keep across a cancelled coroutine.
     *
     * **Answers WAV, and that is forced rather than chosen.** [record] writes
     * MPEG-4/AAC, which is right for a file people attach to a mail and is refused by
     * two of the three providers that can hear at all — Gemini's inline set does not
     * include `audio/mp4` and OpenAI's chat wire takes wav and mp3 and nothing else.
     * Mono 16 kHz PCM is the one format all of them accept, and it is also what every
     * speech model resamples to anyway, so nothing is lost by starting there.
     *
     * Suspends for the whole clip, on [record]'s reasoning. Nothing throws.
     */
    suspend fun capture(request: CaptureRequest): CaptureOutcome =
        CaptureOutcome(error = "Recording is not available here")

    /**
     * Begins listening and returns at once, answering a problem or `""`.
     *
     * [record]'s relationship to [start], one family along, and for the same reason: a
     * macro that says "listen while I do something else, then ask about it" cannot be
     * written with a call that blocks for the whole clip. The finished sound arrives at
     * [endCapture].
     *
     * [CaptureRequest.maxSeconds] is a **limit** rather than a length here, and it is not
     * optional: a capture nobody ends would hold the microphone with the system's
     * indicator lit and — unlike [start] — with no file growing on disk to make it
     * visible. When the limit is reached the microphone is released and the clip is
     * **kept** for [endCapture] to collect, which is the least surprising reading of "a
     * limit": the macro asked to listen for up to that long and gets what there was.
     */
    suspend fun beginCapture(request: CaptureRequest): String =
        "Recording is not available here"

    /**
     * Ends the running capture, or collects one its own limit already ended.
     *
     * With nothing listening and nothing waiting to be collected this is a worded error
     * rather than a silent empty clip, on [stop]'s reasoning: a macro reaching a stop it
     * did not start is usually a graph that ran in an order its author did not expect.
     */
    suspend fun endCapture(): CaptureOutcome =
        CaptureOutcome(error = "Recording is not available here")

    /** Whether a recording **or** a capture is running right now. */
    fun isRecording(): Boolean
}

/**
 * How long to listen for, and what should end it.
 *
 * [RecordingRequest]'s reasoning for being an object rather than two parameters, and
 * deliberately *not* that class: this one has no folder and no name, because nothing
 * it produces is kept.
 */
data class CaptureRequest(
    /**
     * The longest the clip may run, clamped to [CaptureLimits.MAX_SECONDS].
     *
     * Not optional and with no "forever" value, on `action.listen`'s argument: an open
     * microphone holds the hardware with the system's indicator lit and — unlike
     * [start] — has no file growing on disk to make that visible. Here the clamp does
     * a second job, because 16 kHz mono PCM is thirty-two kilobytes a second, so the
     * number of seconds *is* the heap bound.
     */
    val maxSeconds: Int = 15,
    /**
     * How much quiet ends the clip early, or `0` to run to [maxSeconds].
     *
     * **Zero means something different here than it does on `action.listen`**, and the
     * label has to say so. There, zero hands end-of-speech detection to the platform,
     * which is far better at it than any number a user could pick. There is no platform
     * detector behind a raw microphone read, so zero here can only mean "do not stop
     * early" — and a field whose zero silently meant the opposite of the same field one
     * node over would be a lie the user has no way to catch.
     */
    val silenceSeconds: Int = 3,
)

/**
 * A clip, held in memory.
 *
 * Base64 rather than a `ByteArray` for `Files.readBytes`' reason: it is the form every
 * provider wants, and holding both would double what a foreground service carries.
 *
 * [heard] is separate from a blank [error] because **nothing being said is not a
 * failure**. A macro that listens on a schedule and hears silence has worked exactly
 * as asked; reporting that as an error would put it in the run log in red every time
 * the room was quiet. `action.listen` draws the same line with its `nothing` port.
 *
 * [durationMs] is `-1` when unknown, never `0`, on [RecordingOutcome]'s rule: a zero
 * here reads as "it recorded nothing", which is a different and much more alarming
 * thing than "it does not say".
 */
data class CaptureOutcome(
    val base64: String = "",
    val mediaType: String = "",
    val durationMs: Long = -1,
    val heard: Boolean = false,
    val error: String = "",
)

/** What a capture may not exceed, whatever a node's config says. */
object CaptureLimits {

    /**
     * Two minutes, which is both a courtesy and a heap bound.
     *
     * `AndroidSpeech.listen` clamps its own cap rather than trusting config, and the
     * same argument applies twice over here: an open microphone is visible to the user
     * the whole time it is open, and this one is accumulating thirty-two kilobytes a
     * second in memory while it does. Two minutes is under four megabytes, which lines
     * up with [AudioLimits.MAX_MODEL_BYTES] — so a clip that reaches the clamp is still
     * a clip a model will accept.
     */
    const val MAX_SECONDS: Int = 120

    /** The rate every speech model resamples to anyway, so nothing is gained by more. */
    const val SAMPLE_RATE_HZ: Int = 16_000
}

/**
 * What one recording should be.
 *
 * A request object rather than five parameters, on `PhotoRequest`'s reasoning: two of the
 * three nodes pass the same thing, and a positional list they share is a list they can
 * disagree about.
 */
data class RecordingRequest(
    /**
     * Where the file goes, read the way `action.file_write` reads a path. Blank means the
     * app's own storage, which needs no grant.
     */
    val toFolder: String = "",
    /** The file name, with or without an extension. Blank asks for one made from the clock. */
    val name: String = "",
    /**
     * How long the recording may run before it stops itself. The length for
     * [Microphone.record]; a limit for [Microphone.start].
     */
    val seconds: Int = 0,
    val quality: RecordingQuality = RecordingQuality.VOICE,
    val whenExists: WhenExists = WhenExists.KEEP_BOTH,
)

/**
 * How much of the sound to keep.
 *
 * Two rather than a bitrate field, because the number is not a thing anybody knows the right
 * value of, and the two answers people actually want are far apart: a spoken note that
 * should be small enough to send, and everything else.
 */
enum class RecordingQuality {
    /** Mono, speech-rate sampling. A minute is well under a megabyte. */
    VOICE,

    /** Stereo at CD sampling, for anything that is not a voice. */
    HIGH,
}

/**
 * Receipt from a finished recording.
 *
 * `FileResult`'s shape with the two facts only a recording has. [durationMs] and [sizeBytes]
 * are **-1 when unknown, never 0**, on `FileInfoItem`'s rule: a zero here reads as "it
 * recorded nothing", which is a different and much more alarming thing than "the file does
 * not say".
 */
data class RecordingOutcome(
    val changed: Boolean = false,
    /** Where it ended up, which is not where it was asked for if the name changed. */
    val path: String = "",
    val name: String = "",
    val durationMs: Long = -1,
    val sizeBytes: Long = -1,
    val error: String = "",
)

/**
 * A recording that exists, as `trigger.recording_saved` reports it.
 *
 * Separate from [RecordingOutcome] for the reason `ImageRecord` is separate from
 * `ImageWrite`: a receipt answers "did what I asked work?", and an event answers "here is a
 * thing" — a `changed` flag on the second would always be true and would mean nothing.
 *
 * Epoch millis rather than a `DateTime`, because `core` may not import `domain`; the trigger
 * converts as it builds its item.
 */
data class RecordingRecord(
    val path: String = "",
    val name: String = "",
    /** The folder as a person recognises it, or blank for the app's own storage. */
    val folder: String = "",
    val mimeType: String = "",
    val durationMs: Long = -1,
    val sizeBytes: Long = -1,
    val recordedAtEpochMs: Long = -1,
)

/** The engine's default: no microphone, failing closed with a sentence that says so. */
object NoMicrophone : Microphone {
    override suspend fun record(request: RecordingRequest) = RecordingOutcome(error = UNAVAILABLE)
    override suspend fun start(request: RecordingRequest) = UNAVAILABLE
    override suspend fun stop() = RecordingOutcome(error = UNAVAILABLE)
    override suspend fun capture(request: CaptureRequest) = CaptureOutcome(error = UNAVAILABLE)
    override suspend fun beginCapture(request: CaptureRequest) = UNAVAILABLE
    override suspend fun endCapture() = CaptureOutcome(error = UNAVAILABLE)
    override fun isRecording(): Boolean = false

    private const val UNAVAILABLE = "Recording is not available on this phone"
}
