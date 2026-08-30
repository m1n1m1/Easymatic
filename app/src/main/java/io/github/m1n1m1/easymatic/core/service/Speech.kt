package io.github.m1n1m1.easymatic.core.service

/**
 * Saying things out loud and hearing what is said back — `action.speak`,
 * `action.speak_stop`, `action.listen` and `value.speaking`.
 *
 * Reached from `ExecutionContext` and nothing else, on [Microphone]'s and [Files]'
 * reasoning. **Nothing here throws**: every member answers a result carrying an error
 * string, so a node reports what happened and takes a branch rather than unwinding a run.
 * Both halves of this are unusually easy to be refused — no engine installed, no voice data
 * for the language, another app holding the microphone, a recognition service that is
 * simply absent — and not one of those is a reason for a macro to stop.
 *
 * ### Why speaking and listening are one facade
 *
 * They are the two directions of one conversation, and the macro that motivates either uses
 * both: "say *what should I do?*, then hear the answer" is one thought and one node pair.
 * [HomeAssistant] already sets the precedent for a facade whose members differ this much in
 * cost — a cached state read and a networked service call — and the argument is the same
 * one: what belongs together is the *subject*, not the transport.
 *
 * The alternative was to hang [listen] off [Microphone], and it is worth saying why not.
 * That facade is about **recordings**: it produces a file, in a folder, with a name, which
 * `trigger.recording_saved` then reports and the six `action.file_*` nodes can address.
 * Recognition produces none of those. It uses the same hardware and shares nothing else.
 *
 * ### What that costs, and where it is paid
 *
 * One microphone means [listen] and a running `action.record_audio` genuinely contend, and
 * the loser is told so in a sentence rather than made to wait — [Microphone]'s "there is one
 * microphone" rule read from the other side. Both routes claim the engine service's
 * microphone foreground type through the same counted holder, so the *type* composes even
 * where the hardware does not.
 *
 * ### Which members the pull side may touch
 *
 * Only [isSpeaking], and for [Microphone.isRecording]'s reason exactly: it is a flag this
 * process set itself, so "cheap, repeatable and cannot fail" is not a judgement about it but
 * a description of what it is. [speak] and [listen] hold hardware for seconds at a time and
 * are squarely actions' work.
 */
interface Speech {

    /**
     * Speaks [SpeechRequest.text], answering what happened.
     *
     * When [SpeechRequest.waitForCompletion] is true this suspends until the utterance
     * finishes, and cancelling the caller stops it mid-word — which is how a macro disabled
     * mid-sentence goes quiet. When it is false the utterance outlives the call, until its
     * own end or until [stop].
     *
     * Waiting is the **default** on the node, and that default is load-bearing rather than
     * cautious: "speak a question, then listen for the answer" is the commonest graph this
     * facade exists for, and a recognizer opened while the phone is still talking hears the
     * phone.
     */
    suspend fun speak(request: SpeechRequest): SpeechOutcome

    /**
     * Stops whatever is being spoken, and empties the queue. Answers whether anything was
     * actually stopped.
     *
     * `SystemServices.stopSounds`' shape, and it exists for the same reason: an utterance
     * started with [SpeechRequest.waitForCompletion] false has nothing else that will ever
     * end it early. A caller waiting on one is released rather than cancelled — the speech
     * ends, the workflow carries on.
     */
    fun stop(): Boolean

    /** Whether something is being spoken right now. */
    fun isSpeaking(): Boolean

    /**
     * Listens for one utterance and answers what was heard.
     *
     * Suspends for as long as somebody is speaking, bounded by [ListenRequest.maxSeconds].
     * Cancelling the caller ends the attempt and releases the microphone.
     *
     * **Hearing nothing is not a failure.** Silence answers a blank [ListenOutcome.error];
     * only something the user could act on — no recognition service, the grant refused, the
     * microphone already held — fills the error in. The node logs those at two different
     * levels, so collapsing them here would collapse them everywhere downstream.
     *
     * **The cap is enforced here rather than by the caller**, which is the one place this
     * departs from `Prompts`, where the node owns the timeout and the facade has none. A
     * dialog left open forever is untidy; a recognizer left open forever holds the
     * microphone with the system's indicator lit. So there is exactly one timeout, it is
     * [ListenRequest.maxSeconds], and hitting it is reported as [ListenOutcome.timedOut]
     * rather than inferred from a null.
     */
    suspend fun listen(request: ListenRequest): ListenOutcome

    /**
     * Begins recognising and returns at once, answering a problem or `""`.
     *
     * [Microphone.beginCapture]'s relationship to [Microphone.capture], one facade along
     * and for the same reason: [listen] suspends for the whole utterance, so a macro
     * cannot transcribe *while* doing something else. The words arrive at [endListening].
     *
     * **This is `SpeechRecognizer.stopListening`'s whole purpose**, and it is the reason
     * the pair can exist at all. That API is built around somebody who stops talking on
     * their own; `stopListening` is the escape hatch for a caller who decides instead —
     * it ends the utterance and the results come back through the same callback, so a
     * session is the platform's own shape rather than something imposed on it.
     *
     * [ListenRequest.maxSeconds] is a limit rather than a length here, on
     * [Microphone.beginCapture]'s reasoning: a recogniser nobody ends holds the
     * microphone with the system's indicator lit, and there is no file growing on disk to
     * make that visible.
     */
    suspend fun beginListening(request: ListenRequest): String = NOT_AVAILABLE

    /**
     * Ends the running recognition and answers with what was heard.
     *
     * Reaching this with nothing running is an error rather than silence, on
     * `action.record_stop`'s rule: a macro that reached a stop it never started is
     * usually a graph whose branches ran in an order its author did not expect.
     */
    suspend fun endListening(): ListenOutcome = ListenOutcome(error = NOT_AVAILABLE)

    /**
     * Whether a [beginListening] session is open right now.
     *
     * [Microphone.isRecording]'s twin, and it exists for one caller: `action`
     * `.transcribe_end` has to know which of the two sessions Start opened, and asking is
     * the only answer that cannot disagree with what Start actually did. A second engine
     * dropdown on the End node could — and a user who changed one and not the other would
     * get a node that collects nothing, with both cards looking correctly filled in.
     *
     * Not suspending and cannot fail, on [isSpeaking]'s reasoning: it reads a flag this
     * process set itself.
     */
    fun isTranscribing(): Boolean = false
}

private const val NOT_AVAILABLE = "Speech recognition is not available on this phone"

/**
 * What one utterance should be.
 *
 * A request object rather than six parameters, on [RecordingRequest]'s reasoning.
 */
data class SpeechRequest(
    val text: String,
    /**
     * A BCP-47 tag — `de-DE`, `en-GB`. Blank means the phone's own language, which is what
     * almost every macro wants and what an unset field therefore has to mean.
     *
     * A tag with no voice installed falls back to the device default rather than failing,
     * and says so in the outcome's error: silence would be the worse answer, since the
     * macro's next node is usually a question this one just asked.
     */
    val language: String = "",
    /** 1 is the engine's normal pace. Clamped by the implementation rather than validated. */
    val rate: Float = 1f,
    /** 1 is the engine's normal pitch. */
    val pitch: Float = 1f,
    /**
     * Which volume this follows. [AudioStream.MEDIA] by default, because a phone silenced
     * for ringing is usually still expected to answer when spoken to deliberately.
     */
    val stream: AudioStream = AudioStream.MEDIA,
    /** Whether to suspend until the utterance ends. See [Speech.speak]. */
    val waitForCompletion: Boolean = true,
    /**
     * Whether to queue behind whatever is already being spoken rather than replacing it.
     *
     * False — replace — is the default because the commonest reason to speak twice in quick
     * succession is a macro re-firing, and hearing the previous run's sentence finish before
     * this one starts is worse than losing it.
     */
    val queue: Boolean = false,
)

/**
 * Receipt from one utterance.
 *
 * [spoke] is false whenever nothing was said aloud, and [error] then always says why — there
 * is no silent third case, which is [RecordingOutcome]'s arrangement.
 */
data class SpeechOutcome(
    val spoke: Boolean = false,
    val error: String = "",
)

/** What one listening attempt should be. */
data class ListenRequest(
    /** A BCP-47 tag. Blank means the phone's own language; see [SpeechRequest.language]. */
    val language: String = "",
    /**
     * Whether to prefer a recognizer that runs on the device.
     *
     * True by default, and this is the setting the battery question actually turns on: an
     * on-device recognizer never wakes the radio, where the networked one uploads audio for
     * the length of the utterance. It is a *preference* rather than a requirement because a
     * phone with no on-device model for the language would otherwise be unable to listen at
     * all, which is a worse answer than a round trip.
     */
    val preferOffline: Boolean = true,
    /**
     * The longest the attempt may run before it gives up, in seconds.
     *
     * Not optional, on [RecordingRequest.seconds]' reasoning made sharper: a recognizer
     * nobody stops holds the microphone with the system's recording indicator lit, and
     * unlike a recording there is no file growing on disk to make that visible.
     */
    /**
     * Whether to let the recogniser work the language out and switch to it.
     *
     * **Separate from [language] rather than encoded as a blank one**, because the two are
     * independent on the wire: switching still starts from a language, and a request can
     * legitimately name one *and* allow the recogniser to move off it. Folding them into a
     * single "blank means detect" would make those two states unexpressible and would give
     * the field two meanings, which is the ambiguity the node's own enum exists to remove.
     *
     * Honoured from Android 14 and only where the recogniser implements it. Everywhere else
     * it is silently inert and the request transcribes in [language] — which is the
     * behaviour that existed before this field, so an unsupported phone loses nothing.
     */
    val detect: Boolean = false,
    /**
     * Whether to keep listening across pauses until something stops it.
     *
     * **An explicit flag rather than "silenceSeconds is zero", and that ambiguity is what
     * caused the bug this field exists to fix.** Zero means *"let the phone decide"* to
     * `action.listen`, where the platform's own end-of-speech detection is exactly right,
     * and *"do not stop early"* to the transcription nodes. One field cannot mean both, and
     * reading the intent off it made a session that was configured never to stop end at the
     * first pause.
     *
     * `SpeechRecognizer` has no setting for this: it is built around a single utterance, and
     * every extra that claims otherwise is documented as one the recogniser may ignore — as
     * segmented session mode turned out to be. So this is honoured by **restarting** the
     * recogniser each time it ends and joining what it heard, which is how continuous
     * dictation is done on Android and works on every version.
     */
    val continuous: Boolean = false,
    val maxSeconds: Int = 15,
    /**
     * How much silence ends the utterance, in seconds. Zero leaves it to the platform's own
     * end-of-speech detection, which is what a person expects and what nearly every macro
     * should use.
     */
    val silenceSeconds: Int = 0,
)

/**
 * What one listening attempt heard.
 *
 * [confidence] is **-1 when unknown, never 0**, on [RecordingOutcome]'s rule: a zero here
 * reads as "it understood nothing", which is a different and much more alarming thing than
 * "the recognizer did not say".
 *
 * **Three endings, and the node sends each somewhere different**: something was heard;
 * [timedOut]; or neither, which covers both nobody speaking and the recognizer refusing.
 * [error] separates those last two for the console without splitting the branch, because
 * "say something" and "install a recognizer" are the same instruction to the *graph* and
 * very different ones to the person reading the log.
 */
data class ListenOutcome(
    val heard: Boolean = false,
    val text: String = "",
    val confidence: Float = -1f,
    /**
     * The BCP-47 tag the recogniser says it actually used, or blank when it did not say.
     *
     * Blank rather than the requested language, on [confidence]'s `-1` rule: "the recogniser
     * did not report one" and "it used the one I asked for" are different facts, and a node
     * that filled this in from its own config would be inventing an answer the platform
     * never gave. Only Android 14 and above reports it, and only when detection was asked
     * for.
     */
    val language: String = "",
    /** Whether [ListenRequest.maxSeconds] ran out with nobody having finished speaking. */
    val timedOut: Boolean = false,
    val error: String = "",
)

/** The engine's default: no voice at either end, failing closed with a sentence that says so. */
object NoSpeech : Speech {
    override suspend fun speak(request: SpeechRequest) = SpeechOutcome(error = NO_VOICE)
    override fun stop(): Boolean = false
    override fun isSpeaking(): Boolean = false
    override suspend fun listen(request: ListenRequest) = ListenOutcome(error = NO_EARS)
    override suspend fun beginListening(request: ListenRequest) = NO_EARS
    override suspend fun endListening() = ListenOutcome(error = NO_EARS)
    override fun isTranscribing(): Boolean = false

    private const val NO_VOICE = "Speaking is not available on this phone"
    private const val NO_EARS = "Speech recognition is not available on this phone"
}
