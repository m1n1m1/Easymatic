package com.example.ottomatic.data.speech

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
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.ottomatic.core.capabilities.CapabilityStatus
import com.example.ottomatic.core.service.AudioStream
import com.example.ottomatic.core.service.ListenOutcome
import com.example.ottomatic.core.service.ListenRequest
import com.example.ottomatic.core.service.Speech
import com.example.ottomatic.core.service.SpeechOutcome
import com.example.ottomatic.core.service.SpeechRequest
import com.example.ottomatic.data.service.ServiceForeground
import com.example.ottomatic.domain.registry.SpeechLanguages
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * installed language list out, mirroring what `OttomaticAccessibilityService` does on
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

    private val engineLock = Mutex()

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
        val id = "ottomatic-${utteranceIds.incrementAndGet()}"
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
            withTimeoutOrNull(cap * MILLIS_PER_SECOND) { listenOnce(request) }
                ?: ListenOutcome(timedOut = true)
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

    private fun recognitionIntent(request: ListenRequest): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, request.preferOffline)
            if (request.language.isNotBlank()) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, request.language)
            }
            if (request.silenceSeconds > 0) {
                val millis = request.silenceSeconds * MILLIS_PER_SECOND
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, millis)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, millis)
            }
        }

    private fun listenerAnswering(answer: (ListenOutcome) -> Unit) = object : RecognitionListener {
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
            answer(ListenOutcome(heard = true, text = text, confidence = confidence))
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
            ListenOutcome(error = "Ottomatic has not been granted microphone access")

        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            ListenOutcome(error = "Something else is using the microphone")

        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> ListenOutcome(error = "Speech recognition could not reach the network")

        SpeechRecognizer.ERROR_AUDIO ->
            ListenOutcome(error = "The microphone could not be read")

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
            capabilitiesChanged?.invoke()
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
         * `OttomaticAccessibilityService.instance` takes, and for the same reason.
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
