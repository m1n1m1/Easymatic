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

    /** Whether a recording is running right now. */
    fun isRecording(): Boolean
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
    override fun isRecording(): Boolean = false

    private const val UNAVAILABLE = "Recording is not available on this phone"
}
