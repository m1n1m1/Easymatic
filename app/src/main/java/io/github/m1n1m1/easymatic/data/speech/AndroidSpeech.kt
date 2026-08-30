package io.github.m1n1m1.easymatic.data.speech

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.github.m1n1m1.easymatic.core.capabilities.CapabilityStatus
import io.github.m1n1m1.easymatic.core.service.AudioStream
import io.github.m1n1m1.easymatic.core.service.ListenOutcome
import io.github.m1n1m1.easymatic.core.service.ListenRequest
import io.github.m1n1m1.easymatic.core.service.Speech
import io.github.m1n1m1.easymatic.core.service.SpeechOutcome
import io.github.m1n1m1.easymatic.core.service.SpeechRequest
import io.github.m1n1m1.easymatic.data.service.ServiceForeground
import io.github.m1n1m1.easymatic.domain.registry.SpeechLanguages
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [Speech] over `TextToSpeech` and `SpeechRecognizer`.
 *
 * ### Why the engine is built once and lazily
 *
 * A `TextToSpeech` spawns a connection to whichever engine app the phone uses, and its
 * readiness arrives on an **asynchronous init callback** rather than from the constructor.
 * Two consequences, and both are load-bearing.
 *
 * The first is that the very first `speak` in a process would otherwise silently do nothing:
 * `TextToSpeech.speak` answers `SUCCESS` for an utterance queued against an engine that has
 * not initialised, and the phone simply stays quiet. So every call awaits one shared
 * [CompletableDeferred] — built on first use, because a user who never speaks should not pay
 * an engine spawn at startup, exactly as `WebViewScriptEngine` reasons about its sandbox.
 *
 * The second is that **this is where the synthesis capability is learned**. Nothing can
 * answer "does this phone have a voice?" before an engine has initialised once, which is why
 * `DeviceCapability.SPEECH_SYNTHESIS` is the second capability to need
 * [CapabilityStatus.UNKNOWN]. Once init resolves, [republish] pushes both the answer and the
 * installed language list out, mirroring what `EasymaticAccessibilityService` does on
 * connect.
 *
 * ### Why in-flight utterances are tracked here rather than asked of the engine
 *
 * `TextToSpeech.isSpeaking` reports the engine's own state, which lags an enqueue by however
 * long the engine takes to start — so `value.speaking` read immediately after a
 * fire-and-forget `action.speak` would answer false, and a graph guarding against talking
 * over itself would be guarding against nothing. Ids go into [inFlight] at **enqueue** time
 * and come out on the progress callback, which makes the flag mean what the facade's KDoc
 * claims: something this process asked for has not finished.
 *
 * It is also what makes `waitForCompletion = false` survivable at all. Such an utterance has
 * nothing left holding it, so its audio focus and its accounting have to be owned by the
 * listener rather than by the caller.
 *
 * ### Why audio focus is counted rather than scoped
 *
 * For the same reason: a fire-and-forget utterance outlives the call that queued it, so
 * focus cannot be released in a `finally`. It is taken when [inFlight] becomes non-empty and
 * abandoned when it empties — which also means two overlapping utterances cost one focus
 * change rather than two, and the second does not hand the music back mid-sentence.
 *
 * ### What recognition needs that recording already taught us
 *
 * `SpeechRecognizer` must be created and driven from the **main thread** — it is one of the
 * few platform classes that says so outright and throws otherwise — and from Android 11 the
 * engine service reaches the microphone only while its declared foreground type says so. The
 * second is [ServiceForeground.withMicrophone], whose claims are counted, so listening
 * during a running recording costs one platform call and neither cancels the other's type.
 * The hardware itself is still exclusive; that contention is reported as a sentence.
 */
@Suppress("TooManyFunctions") // The four facade members plus one helper each for the engine,
// the focus, the recognizer and the two capability publications.
class AndroidSpeech(
    context: Context,
    private val scope: CoroutineScope,
) : Speech {

    private val appContext = context.applicationContext

    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    init {
        // At construction, so the language chooser is populated for anybody who opens a
        // transcription node — see publishListeningLanguages.
        publishListeningLanguages()
    }

    private val engineLock = Mutex()

    /** Guards [session], so "is one running?" and "open one" are a single decision. */
    private val sessionLock = Mutex()

    @Volatile
    private var session: Session? = null

    @Volatile
    private var engine: TextToSpeech? = null

    @Volatile
    private var ready: CompletableDeferred<Boolean>? = null

    /** Utterance id to its completion, holding the error text or `""`. See the class KDoc. */
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<String>>()

    private val utteranceIds = AtomicLong()

    private val focusLock = Any()

    private var focusRequest: AudioFocusRequest? = null

    // ---------------------------------------------------------------- speaking

    override fun isSpeaking(): Boolean = inFlight.isNotEmpty()

    override fun stop(): Boolean {
        val was = inFlight.keys.toList()
        engine?.stop()
        // Completed rather than cancelled, so a caller waiting on one is released and its
        // macro carries on: `SystemServices.stopSounds`' contract, and the same reasoning.
        was.forEach { finishUtterance(it, error = "") }
        return was.isNotEmpty()
    }

    @Suppress("ReturnCount") // A guard chain, and each exit is a different answer: nothing to
    // say, no engine, the engine refused, queued without waiting, and the finished utterance.
    override suspend fun speak(request: SpeechRequest): SpeechOutcome {
        if (request.text.isBlank()) return SpeechOutcome(error = "There is nothing to say")
        val tts = engineOrNull() ?: return SpeechOutcome(error = NO_ENGINE)
        val languageProblem = applyVoice(tts, request)
        // `TextToSpeech` **drops** an utterance longer than its limit and answers ERROR for
        // it, which on a node whose entire output is sound is the quietest failure available:
        // the phone just stays silent. Speaking the first few thousand characters is a better
        // answer than speaking none of them, and the cut is reported alongside the success.
        val limit = TextToSpeech.getMaxSpeechInputLength()
        val text = request.text.take(limit)
        val lengthProblem = if (request.text.length > limit) {
            "The text is ${request.text.length} characters; said the first $limit"
        } else {
            ""
        }
        val id = "easymatic-${utteranceIds.incrementAndGet()}"
        val done = CompletableDeferred<String>()
        beginUtterance(id, done)
        val mode = if (request.queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
        val queued = runCatching { tts.speak(text, mode, null, id) }.getOrNull()
        if (queued != TextToSpeech.SUCCESS) {
            finishUtterance(id, error = "")
            return SpeechOutcome(error = "The text-to-speech engine refused to speak")
        }
        val problem = listOf(languageProblem, lengthProblem).filter { it.isNotEmpty() }.joinToString(". ")
        if (!request.waitForCompletion) return SpeechOutcome(spoke = true, error = problem)
        val failure = try {
            done.await()
        } catch (cancelled: CancellationException) {
            // Cancelling the caller is what takes an utterance down mid-word, which is how a
            // macro disabled mid-sentence goes quiet. Nothing else would ever stop it.
            engine?.stop()
            finishUtterance(id, error = "")
            throw cancelled
        }
        return SpeechOutcome(
            spoke = failure.isEmpty(),
            error = failure.ifEmpty { problem },
        )
    }

    /**
     * Applies the language, rate, pitch and stream, answering a problem worth reporting or
     * `""`.
     *
     * A missing language is **not** a failure: the engine falls back to the device default
     * and says the sentence in the wrong accent, which the facade's KDoc argues is the right
     * call — silence would be worse, because the macro's next node is usually a question this
     * one just asked. So the problem is carried alongside a successful outcome rather than
     * instead of it.
     */
    @Suppress("ReturnCount") // As above: no language asked for, an unreadable tag, and a
    // language the engine has no data for are three different things to report.
    private fun applyVoice(tts: TextToSpeech, request: SpeechRequest): String {
        tts.setSpeechRate(request.rate.coerceIn(MIN_RATE, MAX_RATE))
        tts.setPitch(request.pitch.coerceIn(MIN_PITCH, MAX_PITCH))
        tts.setAudioAttributes(attributesFor(request.stream))
        if (request.language.isBlank()) return ""
        val locale = runCatching { Locale.forLanguageTag(request.language) }.getOrNull()
            ?: return "'${request.language}' is not a language tag; using the phone's language"
        val applied = runCatching { tts.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        return if (applied == TextToSpeech.LANG_MISSING_DATA || applied == TextToSpeech.LANG_NOT_SUPPORTED) {
            "No voice is installed for ${request.language}; using the phone's language"
        } else {
            ""
        }
    }

    private fun beginUtterance(id: String, done: CompletableDeferred<String>) {
        inFlight[id] = done
        takeFocus()
    }

    /** The single exit for an utterance, however it ended. See the class KDoc. */
    private fun finishUtterance(id: String, error: String) {
        inFlight.remove(id)?.complete(error)
        if (inFlight.isEmpty()) releaseFocus()
    }

    private fun takeFocus() {
        val manager = audioManager ?: return
        synchronized(focusLock) {
            if (focusRequest != null) return
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributesFor(AudioStream.MEDIA))
                .build()
            runCatching { manager.requestAudioFocus(request) }
            focusRequest = request
        }
    }

    private fun releaseFocus() {
        val manager = audioManager ?: return
        synchronized(focusLock) {
            val request = focusRequest ?: return
            runCatching { manager.abandonAudioFocusRequest(request) }
            focusRequest = null
        }
    }

    // ---------------------------------------------------------------- the engine

    /**
     * The initialised engine, or null when this phone has none.
     *
     * The [Mutex] makes "has one been built?" and "build one" a single decision: without it
     * two macros speaking at once spawn two engine connections, and the second orphans the
     * first's progress listener — which would strand every utterance already waiting on it.
     */
    private suspend fun engineOrNull(): TextToSpeech? {
        val existing = ready
        if (existing != null) return if (existing.await()) engine else null
        val gate = engineLock.withLock {
            ready ?: CompletableDeferred<Boolean>().also { gate ->
                ready = gate
                buildEngine(gate)
            }
        }
        return if (gate.await()) engine else null
    }

    private fun buildEngine(gate: CompletableDeferred<Boolean>) {
        val built = runCatching {
            TextToSpeech(appContext) { status ->
                val ok = status == TextToSpeech.SUCCESS
                publishEngineFound(ok)
                if (ok) engine?.setOnUtteranceProgressListener(progressListener)
                republish()
                gate.complete(ok)
            }
        }.getOrNull()
        engine = built
        if (built == null) {
            publishEngineFound(false)
            republish()
            gate.complete(false)
        }
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            utteranceId?.let { finishUtterance(it, error = "") }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            utteranceId?.let { finishUtterance(it, error = "") }
        }

        @Deprecated("Superseded by onError(String, int)", ReplaceWith(""))
        override fun onError(utteranceId: String?) {
            utteranceId?.let { finishUtterance(it, error = SPEAK_FAILED) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            utteranceId?.let { finishUtterance(it, error = SPEAK_FAILED) }
        }
    }

    // ---------------------------------------------------------------- listening

    override suspend fun listen(request: ListenRequest): ListenOutcome {
        if (!recognitionAvailable()) return ListenOutcome(error = NO_RECOGNITION)
        val cap = request.maxSeconds.coerceIn(MIN_LISTEN_SECONDS, MAX_LISTEN_SECONDS)
        return ServiceForeground.withMicrophone {
            if (request.continuous) {
                // Reaching the cap here is the *plan* rather than a failure — the caller
                // asked to listen for the whole time — so this answers what was heard
                // instead of `timedOut`, which is the other branch's meaning.
                openAndAwait(request, cap)
            } else {
                withTimeoutOrNull(cap * MILLIS_PER_SECOND) { listenOnce(request) }
                    ?: ListenOutcome(timedOut = true)
            }
        }
    }

    /** One continuous stretch, ended by its own cap. */
    private suspend fun openAndAwait(request: ListenRequest, capSeconds: Int): ListenOutcome {
        val opened = withContext(Dispatchers.Main.immediate) { openSession(request, capSeconds) }
            ?: return ListenOutcome(error = NO_RECOGNITION)
        return try {
            opened.result.await()
        } finally {
            withContext(NonCancellable) { opened.release() }
        }
    }

    /**
     * Opens a recognition session that runs until [endListening] or its own limit.
     *
     * **The recogniser is held across two calls, which is what makes this different from
     * [listen] rather than a wrapper around it.** `SpeechRecognizer` must be created and
     * driven from the main thread, so the instance, its listener and its result all live in
     * fields guarded by [sessionLock] rather than on a coroutine's stack.
     *
     * The limit is a job rather than a `withTimeoutOrNull`, because nothing is suspended
     * here to time out — [beginListening] returns immediately. It ends the session exactly
     * as [endListening] would, so a macro that never reaches its End node still gives the
     * microphone back.
     */
    @Suppress("ReturnCount") // No recogniser, already running, one that would not open, and
    // begun are four answers a node reports differently.
    override suspend fun beginListening(request: ListenRequest): String {
        if (!recognitionAvailable()) return NO_RECOGNITION
        val refusal = sessionLock.withLock {
            if (session != null) "Already transcribing" else null
        }
        if (refusal != null) return refusal
        val cap = request.maxSeconds.coerceIn(MIN_LISTEN_SECONDS, MAX_LISTEN_SECONDS)
        // A session is continuous by definition: it exists precisely so a macro can talk
        // across pauses while doing something else.
        val opened = withContext(Dispatchers.Main.immediate) {
            openSession(request.copy(continuous = true), cap)
        } ?: return NO_RECOGNITION
        sessionLock.withLock { session = opened }
        // Held for the length of the session rather than by a block around either call,
        // exactly as `AndroidMicrophone.begin` holds the same claim for a recording.
        scope.launch { ServiceForeground.withMicrophone { opened.result.join() } }
        return ""
    }

    override fun isTranscribing(): Boolean = session != null

    override suspend fun endListening(): ListenOutcome {
        val running = sessionLock.withLock { session } ?: return ListenOutcome(error = NOTHING_RUNNING)
        withContext(Dispatchers.Main.immediate) { running.stop() }
        val outcome = withTimeoutOrNull(RESULT_GRACE_MS) { running.result.await() }
            ?: ListenOutcome(error = "The recogniser did not answer in time")
        sessionLock.withLock { if (session === running) session = null }
        withContext(NonCancellable) { running.release() }
        return outcome
    }

    /** Creates the recogniser and starts it. Main thread only. */
    private fun openSession(request: ListenRequest, capSeconds: Int): Session? {
        val recognizer = createRecognizer(request) ?: return null
        val opened = Session(recognizer, request, capSeconds)
        return if (opened.start()) opened else null
    }

    /**
     * One stretch of listening, however many utterances that takes.
     *
     * **The recogniser is restarted rather than persuaded to stay open, and that is forced
     * rather than chosen.** `SpeechRecognizer` is built around a *single* utterance: it ends
     * on end-of-speech and there is no setting that reliably prevents it. Every extra that
     * appears to offer one — the silence lengths, segmented session mode — carries the same
     * sentence in its documentation, "depending on the recognizer implementation, this value
     * may have no effect", and segmented mode was tried here first and turned out to be one
     * of the ones with no effect. So a long listen is many short ones joined up, which is
     * how continuous dictation is done on Android and needs nothing of the recogniser but
     * what it already promises.
     *
     * **A silent stretch is a restart, not an ending.** `ERROR_NO_MATCH` and
     * `ERROR_SPEECH_TIMEOUT` are exactly what a pause produces, and treating them as the end
     * of the session is the bug this class was written to fix. They end an *utterance*; only
     * [stop], the cap, or a real failure end the session.
     */
    private inner class Session(
        private val recognizer: SpeechRecognizer,
        private val request: ListenRequest,
        capSeconds: Int,
    ) {
        val result = CompletableDeferred<ListenOutcome>()

        private val heard = StringBuilder()
        private var detected = ""

        /** True once something has asked the session to end; restarts stop from then on. */
        private var stopping = false

        /** True between `startListening` and whichever callback ends that utterance. */
        private var listening = false

        /**
         * Mutes the recogniser's tones from the first restart onward.
         *
         * Created eagerly and used late: nothing is muted until [carryOn] actually restarts,
         * so the first listen still says "speak now" the way `action.listen` always has.
         */
        private val beeps = RecognitionBeeps(appContext)

        /**
         * Restarts so far, purely as a runaway guard.
         *
         * A recogniser that fails instantly and forever would otherwise spin the main thread
         * for the whole cap. The bound is generous because a real minute of speech is a
         * couple of dozen utterances, not hundreds.
         */
        private var restarts = 0

        private val limit = scope.launch {
            delay(capSeconds.toLong() * MILLIS_PER_SECOND)
            withContext(Dispatchers.Main.immediate) { stop() }
        }

        fun start(): Boolean {
            recognizer.setRecognitionListener(listener)
            return begin()
        }

        private fun begin(): Boolean {
            listening = true
            val ok = runCatching { recognizer.startListening(recognitionIntent(request)) }.isSuccess
            if (!ok) {
                listening = false
                finish(ListenOutcome(error = NO_RECOGNITION))
            }
            return ok
        }

        /** Ends the utterance and, with it, the session. Main thread only. */
        fun stop() {
            if (stopping) return
            stopping = true
            limit.cancel()
            if (!listening) {
                // Between utterances there is nothing to stop and no callback coming, so
                // waiting for one would hang until the grace ran out and report a timeout
                // over a session that worked perfectly.
                finish(collected())
                return
            }
            // `stopListening`, never `cancel`: the first lets the last utterance come back
            // through the listener, and the second throws it away.
            runCatching { recognizer.stopListening() }
        }

        /** Releases the hardware and the streams. Safe to call more than once. */
        suspend fun release() {
            limit.cancel()
            result.complete(collected())
            withContext(Dispatchers.Main.immediate) {
                runCatching { recognizer.cancel() }
                runCatching { recognizer.destroy() }
            }
            // Last, and on every route out of the session — a mute that outlived the macro
            // that made it would leave somebody's music off with nothing to say why.
            beeps.restore()
        }

        private fun collected(): ListenOutcome {
            val text = heard.toString()
            return if (text.isBlank()) {
                ListenOutcome()
            } else {
                ListenOutcome(heard = true, text = text, language = detected)
            }
        }

        private fun finish(outcome: ListenOutcome) {
            stopping = true
            limit.cancel()
            result.complete(outcome)
        }

        /** An utterance ended benignly: carry on unless something has asked us to stop. */
        private fun carryOn() {
            listening = false
            if (stopping || result.isCompleted) {
                finish(collected())
                return
            }
            if (restarts++ >= MAX_RESTARTS) {
                finish(collected())
                return
            }
            // Before the restart, not after: the tone plays as `startListening` is called.
            beeps.silence()
            begin()
        }

        private val listener = object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull { it.isNotBlank() }
                    ?.let {
                        if (heard.isNotEmpty()) heard.append(' ')
                        heard.append(it)
                        // Something was said, so this is not a recogniser spinning on
                        // nothing and the runaway guard should not count it.
                        restarts = 0
                    }
                carryOn()
            }

            override fun onError(error: Int) {
                listening = false
                val outcome = outcomeForError(error)
                // A pause reads as "no match" or "speech timeout", and ending the session
                // there is precisely what this class exists to stop.
                if (outcome.error.isBlank()) {
                    carryOn()
                    return
                }
                // Anything the user could act on ends it — but not before handing back what
                // was already heard, which is real output the macro asked for.
                finish(if (heard.isNotEmpty()) collected() else outcome)
            }

            override fun onLanguageDetection(results: Bundle) {
                detected = results.getString(DETECTED_LANGUAGE).orEmpty()
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }
    }

    /**
     * One recognition attempt, driven from the main thread as `SpeechRecognizer` requires.
     *
     * `invokeOnCancellation` is what releases the microphone when the cap in [listen] runs
     * out — the recognizer has no timeout of its own that covers a user who starts speaking
     * and never stops, so the coroutine's cancellation is the only thing that ends it.
     *
     * The listener resumes exactly once, guarded by [answered], because the platform is free
     * to deliver `onError` after `onResults` for the same attempt — `OverlayPrompts` guards
     * the same hazard for the same reason.
     */
    private suspend fun listenOnce(request: ListenRequest): ListenOutcome =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val recognizer = createRecognizer(request)
                if (recognizer == null) {
                    continuation.resume(ListenOutcome(error = NO_RECOGNITION))
                    return@suspendCancellableCoroutine
                }
                var answered = false
                fun answer(outcome: ListenOutcome) {
                    if (answered) return
                    answered = true
                    runCatching { recognizer.destroy() }
                    continuation.resume(outcome)
                }
                recognizer.setRecognitionListener(listenerAnswering(::answer))
                continuation.invokeOnCancellation {
                    runCatching { recognizer.cancel() }
                    runCatching { recognizer.destroy() }
                }
                runCatching { recognizer.startListening(recognitionIntent(request)) }
                    .onFailure { answer(ListenOutcome(error = NO_RECOGNITION)) }
            }
        }

    private fun createRecognizer(request: ListenRequest): SpeechRecognizer? = runCatching {
        val onDevice = request.preferOffline &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        if (onDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        } else {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }
    }.getOrNull()

    /**
     * Asks the recogniser to work the language out and switch to it.
     *
     * **One extra does both**, which is why detection is not set separately: the platform
     * documents that enabling the switch "implicitly enables `EXTRA_ENABLE_LANGUAGE_DETECTION`",
     * and detection *alone* would only report a language while still transcribing in the
     * requested one — the reporting without the behaviour.
     *
     * **The candidate set is bounded to the packs that are actually downloaded**, and that
     * is not caution: the platform says "the corresponding language models must be
     * downloaded to support the switch. Otherwise, the recognizer will report an error on a
     * switch failure." Offering the *supported* list — well over a hundred languages, of
     * which a handful are installed — would therefore turn a working transcription into an
     * error the moment somebody spoke a language whose model was missing. So the bound is
     * offered only when `SpeechLanguages` knows it is reporting installed packs; where it
     * does not, the switch is left unbounded and the recogniser decides, which is the same
     * degradation the rest of this feature takes.
     *
     * Both extras are API 34 and this module is minSdk 26, so they are named as literals
     * here — exactly as `ERROR_LANGUAGE_UNAVAILABLE` is above, and for its reason.
     */
    private fun Intent.applyLanguageSwitch() {
        if (Build.VERSION.SDK_INT < LANGUAGE_SWITCH_SDK) return
        putExtra(EXTRA_ENABLE_LANGUAGE_SWITCH, LANGUAGE_SWITCH_BALANCED)
        if (SpeechLanguages.listeningIsInstalledOnly()) {
            putStringArrayListExtra(
                EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES,
                ArrayList(SpeechLanguages.forListening()),
            )
        }
    }

    private fun recognitionIntent(request: ListenRequest): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, request.preferOffline)
            if (request.language.isNotBlank()) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, request.language)
            }
            if (request.detect) applyLanguageSwitch()
            // A stated silence is what it says. A continuous request asks for the longest
            // pause the recogniser will tolerate inside *one* utterance — not to hold the
            // session open, which restarting does reliably and this does not, but because
            // every utterance that ends is a restart and every restart is a tone the user
            // hears. It is a hint the recogniser may ignore; where it is honoured, the
            // tones become rare rather than merely silenced.
            val silenceMillis = when {
                request.silenceSeconds > 0 -> request.silenceSeconds * MILLIS_PER_SECOND
                request.continuous -> CONTINUOUS_SILENCE_MS
                else -> 0L
            }
            if (silenceMillis > 0) {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMillis)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMillis)
            }
        }

    private fun listenerAnswering(answer: (ListenOutcome) -> Unit) = object : RecognitionListener {

        /**
         * The tag from [onLanguageDetection], held because it arrives **before** the words.
         *
         * The platform delivers detection as its own callback during the utterance and the
         * transcript separately at the end, so there is nowhere to pass it as a parameter.
         * It is only ever written and read on the main thread, which is where every
         * `SpeechRecognizer` callback is delivered.
         */
        private var detected: String = ""

        override fun onLanguageDetection(results: Bundle) {
            detected = results.getString(DETECTED_LANGUAGE).orEmpty()
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull { it.isNotBlank() }
            if (text == null) {
                answer(ListenOutcome())
                return
            }
            val confidence = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                ?.firstOrNull()
                ?: UNKNOWN_CONFIDENCE
            answer(
                ListenOutcome(
                    heard = true,
                    text = text,
                    confidence = confidence,
                    language = detected,
                ),
            )
        }

        override fun onError(error: Int) = answer(outcomeForError(error))

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    /**
     * What a recognizer error means to a macro.
     *
     * The split that matters is between **nobody spoke** and **something is wrong**: the
     * first two codes are the ordinary silence every listening node will see most often, and
     * reporting those as errors would put a warning in the console every time somebody walked
     * away. Everything else names a thing the user could act on.
     */
    private fun outcomeForError(error: Int): ListenOutcome = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> ListenOutcome()

        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            ListenOutcome(error = "Easymatic has not been granted microphone access")

        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            ListenOutcome(error = "Something else is using the microphone")

        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> ListenOutcome(error = "Speech recognition could not reach the network")

        SpeechRecognizer.ERROR_AUDIO ->
            ListenOutcome(error = "The microphone could not be read")

        // The two the user can actually do something about, and until 2026-08-29 they
        // both read "Speech recognition failed". Transcribing on the phone is only as good
        // as the language pack behind it, and the fix — install it, or switch to an AI
        // model — is invisible unless the message names it.
        ERROR_LANGUAGE_UNAVAILABLE ->
            ListenOutcome(
                error = "The language pack for that language is not installed on this phone — " +
                    "add it in the phone's voice settings, or transcribe with an AI model instead",
            )

        ERROR_LANGUAGE_NOT_SUPPORTED ->
            ListenOutcome(
                error = "This phone's recogniser does not support that language — " +
                    "choose another, or transcribe with an AI model instead",
            )

        else -> ListenOutcome(error = "Speech recognition failed")
    }

    // ---------------------------------------------------------------- capabilities

    private fun recognitionAvailable(): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(appContext) }.getOrDefault(false)

    /**
     * Publishes what the engine turned out to be: both language lists, and a nudge so the
     * Problems panel re-reads the synthesis capability it had to answer UNKNOWN for.
     *
     * Off the init callback's thread, because that callback is delivered on the main looper
     * and one of the two publications sends a broadcast.
     */
    private fun republish() {
        scope.launch {
            publishSpokenLanguages()
            publishHeardLanguages()
            publishInstalledLanguages()
            capabilitiesChanged?.invoke()
        }
    }

    /**
     * Publishes the *listening* languages without waiting for anything to be spoken.
     *
     * **The bug this fixes was invisible and total.** Both language lists used to be
     * published from the text-to-speech init callback, which is reached only by
     * `action.speak` — so on a phone where nobody had ever used a speaking node, the
     * listening language dropdown was empty, drew no chooser button at all, and looked
     * exactly like a plain text box. The field asks for a BCP-47 tag, so an empty chooser
     * is a field almost nobody can fill in correctly.
     *
     * Neither of these needs the engine: one is a broadcast and one is a recogniser query.
     * Speaking's list still waits, because reading it genuinely requires an initialised
     * `TextToSpeech`.
     */
    private fun publishListeningLanguages() {
        scope.launch {
            publishHeardLanguages()
            publishInstalledLanguages()
        }
    }

    private fun publishSpokenLanguages() {
        val tags = runCatching { engine?.availableLanguages.orEmpty() }
            .getOrDefault(emptySet())
            .map { it.toLanguageTag() }
            .sorted()
        SpeechLanguages.hydrateSpeaking(tags)
    }

    /**
     * Asks the recognition service which languages it has, through the one API that answers:
     * an **ordered broadcast** whose result extras carry the list.
     *
     * There is no synchronous call for this, and no callback either — the reply arrives as a
     * result extra on a broadcast nobody receives. A phone with no recognition service simply
     * never fills the extras in, which lands as an empty list, which by
     * `SpeechLanguages`' rule narrows nothing.
     */
    /**
     * Reads the languages whose on-device model is downloaded, from API 33.
     *
     * **`checkRecognitionSupport` is the only API that separates installed from
     * supported**, and that distinction is the whole reason the language dropdown is worth
     * having: the ordered broadcast below answers well over a hundred languages, of which
     * a typical phone has three or four actually downloaded. Offering the long list is
     * offering mostly wrong answers, each of which fails at run time with a message about
     * a language pack.
     *
     * It asks the **on-device** recogniser specifically, because that is the one the
     * offline option uses; the networked recogniser's answer would describe a different
     * engine. A phone that has none, or one that never calls back, simply leaves the
     * installed list unset and `SpeechLanguages.forListening` falls back.
     */
    private suspend fun publishInstalledLanguages() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext) }.getOrDefault(false)) {
            return
        }
        withContext(Dispatchers.Main.immediate) {
            val recognizer = runCatching {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            }.getOrNull() ?: return@withContext
            runCatching {
                recognizer.checkRecognitionSupport(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
                    Executors.newSingleThreadExecutor(),
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(support: RecognitionSupport) {
                            SpeechLanguages.hydrateInstalledListening(
                                support.installedOnDeviceLanguages.sorted(),
                            )
                            recognizer.destroyOnMain()
                        }

                        override fun onError(error: Int) = recognizer.destroyOnMain()
                    },
                )
            }.onFailure { recognizer.destroy() }
        }
    }

    /** `SpeechRecognizer` must be torn down on the main thread, and the callback is not on it. */
    private fun SpeechRecognizer.destroyOnMain() {
        scope.launch(Dispatchers.Main.immediate) { runCatching { destroy() } }
    }

    private fun publishHeardLanguages() {
        if (!recognitionAvailable()) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val tags = getResultExtras(true)
                    ?.getStringArrayList(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES)
                    ?.sorted()
                    .orEmpty()
                SpeechLanguages.hydrateListening(tags)
            }
        }
        runCatching {
            appContext.sendOrderedBroadcast(
                Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS),
                null,
                receiver,
                null,
                Activity.RESULT_OK,
                null,
                null,
            )
        }
    }

    companion object {
        /**
         * Whether a text-to-speech engine was found, or null before one has been built.
         *
         * A process-wide handle rather than a member because `AndroidCapabilityChecker` is
         * constructed fresh at each hydration site and holds nothing — the same shape
         * `EasymaticAccessibilityService.instance` takes, and for the same reason.
         */
        @Volatile
        private var engineFound: Boolean? = null

        /** Set by `ServiceLocator` so a resolved engine can refresh the Problems panel. */
        @Volatile
        var capabilitiesChanged: (() -> Unit)? = null

        /** What `AndroidCapabilityChecker` reads for `DeviceCapability.SPEECH_SYNTHESIS`. */
        fun synthesisStatus(): CapabilityStatus = when (engineFound) {
            true -> CapabilityStatus.AVAILABLE
            false -> CapabilityStatus.UNAVAILABLE
            null -> CapabilityStatus.UNKNOWN
        }

        internal fun publishEngineFound(found: Boolean?) {
            engineFound = found
        }

        private const val NO_ENGINE = "This phone has no text-to-speech engine"
        private const val NO_RECOGNITION = "This phone has no speech recognition"
        private const val SPEAK_FAILED = "The text-to-speech engine could not say that"
        private const val UNKNOWN_CONFIDENCE = -1f
        private const val MILLIS_PER_SECOND = 1_000L
        private const val MIN_RATE = 0.1f
        private const val MAX_RATE = 4f
        private const val MIN_PITCH = 0.1f
        private const val MAX_PITCH = 4f
        private const val NOTHING_RUNNING = "Nothing is transcribing"

        /** Utterances one continuous stretch may join before it gives up. See `Session`. */
        private const val MAX_RESTARTS = 240

        /**
         * The pause a continuous listen asks the recogniser to sit through.
         *
         * Ten seconds: long enough to cover thinking, a door, a sentence restarted, and
         * short enough that a recogniser which honours it still returns text while the macro
         * is running rather than only at the very end.
         */
        private const val CONTINUOUS_SILENCE_MS = 10_000L

        /**
         * How long to wait for results after `stopListening`.
         *
         * The recogniser answers through the same callback rather than returning, so
         * something has to bound the wait — and it is short because by this point the audio
         * is already in: this covers the last decode, not somebody still talking.
         */
        private const val RESULT_GRACE_MS = 15_000L

        /**
         * `ERROR_LANGUAGE_NOT_SUPPORTED` and `ERROR_LANGUAGE_UNAVAILABLE`, as literals.
         *
         * Both are API 33 constants and this module is minSdk 26, so naming them would
         * either fail to resolve or need a version gate around a `when` branch that is
         * harmless on every older phone — an old recogniser simply never sends them.
         */
        /**
         * `EXTRA_ENABLE_LANGUAGE_SWITCH`, `EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES`,
         * `LANGUAGE_SWITCH_BALANCED` and `SpeechRecognizer.DETECTED_LANGUAGE` — all API 34,
         * named as literals for [ERROR_LANGUAGE_UNAVAILABLE]'s reason.
         *
         * `BALANCED` rather than `HIGH_PRECISION` because the latter, in the platform's own
         * words, "may wait for longer before switching" — and this is a bounded utterance,
         * not a running dictation, so a switch that lands after the speaker has finished is
         * a switch that never happened.
         */
        private const val LANGUAGE_SWITCH_SDK = 34
        private const val EXTRA_ENABLE_LANGUAGE_SWITCH = "android.speech.extra.ENABLE_LANGUAGE_SWITCH"
        private const val EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES =
            "android.speech.extra.LANGUAGE_SWITCH_ALLOWED_LANGUAGES"
        private const val LANGUAGE_SWITCH_BALANCED = "balanced"
        private const val DETECTED_LANGUAGE = "detected_language"

        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13

        private const val MIN_LISTEN_SECONDS = 1
        private const val MAX_LISTEN_SECONDS = 120
    }
}

/** Maps a stream onto the attributes a spoken utterance should carry on it. */
private fun attributesFor(stream: AudioStream): AudioAttributes =
    AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .setUsage(
            when (stream) {
                AudioStream.MEDIA -> AudioAttributes.USAGE_MEDIA
                AudioStream.RING -> AudioAttributes.USAGE_NOTIFICATION_RINGTONE
                AudioStream.ALARM -> AudioAttributes.USAGE_ALARM
                AudioStream.NOTIFICATION -> AudioAttributes.USAGE_NOTIFICATION
                AudioStream.SYSTEM -> AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
            },
        )
        .build()
