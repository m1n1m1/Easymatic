package com.example.ottomatic.data.script

import android.content.Context
import android.util.Log
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptConsoleCallback
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import androidx.javascriptengine.SandboxDeadException
import com.example.ottomatic.core.service.ScriptConsoleLevel
import com.example.ottomatic.core.service.ScriptConsoleMessage
import com.example.ottomatic.core.service.ScriptEngine
import com.example.ottomatic.core.service.ScriptOutcome
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ScriptEngine] backed by the V8 inside the device's system WebView, via
 * `androidx.javascriptengine`.
 *
 * Nothing here ships an interpreter: the engine is the WebView the phone already
 * has, reached over Binder, and the evaluation runs in WebView's own sandboxed
 * process. That is what makes a user-written script safe to run from a
 * foreground service — an infinite loop spins a process we do not own and can
 * kill, rather than wedging the macro engine.
 *
 * Availability is a property of the device, not of the app. A phone with no
 * WebView, a disabled one, or one too old to offer a sandbox reports
 * [ScriptOutcome.Unavailable] forever, and `action.script` degrades to its
 * fallbacks rather than failing the run.
 */
class WebViewScriptEngine(context: Context) : ScriptEngine {

    private val appContext = context.applicationContext

    override suspend fun evaluate(source: String, timeoutMs: Long): ScriptOutcome {
        val sandbox = if (JavaScriptSandbox.isSupported()) connect() else null
        return sandbox?.let { runIsolated(it, source, timeoutMs) } ?: ScriptOutcome.Unavailable
    }

    /** Releases the sandbox process. Called when the engine service shuts down. */
    fun close() = closeSandbox()

    /**
     * Evaluates in a throwaway isolate.
     *
     * A fresh isolate per run is what makes one macro's script unable to see
     * another's — and, deliberately, unable to see its own previous run. A
     * script that needs to remember something writes a variable with
     * `action.set_variable`, where the state is visible and inspectable, rather
     * than accumulating invisibly inside an engine.
     *
     * The isolate is closed in a `finally` because that is also how a runaway
     * loop is stopped: cancelling the future abandons the *call*, only closing
     * the isolate ends the *work*. The console buffer is read *before* that, in
     * every branch including the timeout — a script that logs and then loops is
     * exactly the case where those lines are the only diagnosis available.
     */
    private suspend fun runIsolated(
        sandbox: JavaScriptSandbox,
        source: String,
        timeoutMs: Long,
    ): ScriptOutcome {
        val isolate = runCatching { sandbox.createIsolate(startupParameters(sandbox)) }
            .getOrElse { return failure(it, sandbox, ConsoleBuffer()) }
        val console = captureConsole(sandbox, isolate)
        return try {
            withTimeout(timeoutMs) {
                ScriptOutcome.Value(isolate.evaluateJavaScriptAsync(source).await(), console.snapshot())
            }
        } catch (@Suppress("SwallowedException") timeout: TimeoutCancellationException) {
            ScriptOutcome.Error("Script did not finish within $timeoutMs ms", console.snapshot())
        } catch (cancellation: CancellationException) {
            // The macro itself was cancelled (disarm, re-arm, service stop).
            // That is not a script failure and must keep propagating.
            throw cancellation
        } catch (@Suppress("TooGenericExceptionCaught") cause: Exception) {
            failure(cause, sandbox, console)
        } finally {
            runCatching { isolate.close() }
        }
    }

    /**
     * Starts collecting whatever the script writes to `console`.
     *
     * The executor is direct, like the one in [await]: every hop between the
     * Binder thread and the buffer is a hop in which a message can still be in
     * flight when the isolate closes. `clearConsoleCallback` is deliberately not
     * called — it is an extra IPC that does not flush anything and only narrows
     * that window; closing the isolate tears the callback down anyway.
     *
     * Delivery is best-effort by nature. The evaluation result and the console
     * messages travel over different interfaces, and Binder orders calls per
     * interface rather than across them, so a line written immediately before
     * the return can in principle arrive after it. There is no flush API.
     *
     * Feature support tracks the device's WebView, so an older one simply
     * reports nothing rather than failing the run.
     */
    private fun captureConsole(sandbox: JavaScriptSandbox, isolate: JavaScriptIsolate): ConsoleBuffer {
        val buffer = ConsoleBuffer()
        if (!sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_CONSOLE_MESSAGING)) return buffer
        runCatching {
            isolate.setConsoleCallback(Runnable::run) { message -> buffer.add(message) }
        }
        return buffer
    }

    /**
     * Turns a platform failure into a message, and forgets a sandbox that has
     * died so the next script reconnects instead of inheriting the corpse.
     *
     * Every exception the library raises is a subclass of one we cannot
     * distinguish usefully for the user — a syntax error, a thrown `TypeError`
     * and a heap breach all mean "your script did not produce a value" — so the
     * message is passed through rather than classified.
     */
    private suspend fun failure(
        cause: Throwable,
        sandbox: JavaScriptSandbox,
        console: ConsoleBuffer,
    ): ScriptOutcome {
        if (cause is SandboxDeadException) forget(sandbox)
        return ScriptOutcome.Error(cause.message ?: cause::class.java.simpleName, console.snapshot())
    }

    /**
     * Caps the isolate's heap where the device's WebView supports it, so a
     * runaway allocation is stopped by the platform rather than by the timeout.
     * Feature availability tracks the WebView version, not ours, so it has to be
     * asked rather than assumed.
     */
    private fun startupParameters(sandbox: JavaScriptSandbox): IsolateStartupParameters {
        val parameters = IsolateStartupParameters()
        if (sandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)) {
            parameters.setMaxHeapSizeBytes(MAX_HEAP_BYTES)
        }
        return parameters
    }

    private suspend fun connect(): JavaScriptSandbox? = connect(appContext)

    /**
     * The sandbox, and the lock guarding it.
     *
     * **Process-wide, not per instance.** The platform permits exactly one
     * `JavaScriptSandbox` per process and throws `IllegalStateException` on a
     * second, so this state has to be scoped the way the constraint is. Held on
     * an instance it appeared to work — `ServiceLocator` builds exactly one
     * engine — right up until anything built a second one, at which point every
     * script in the app reported "unavailable" for the rest of the process, with
     * a swallowed exception as the only trace.
     */
    private companion object {
        private val connectMutex = Mutex()
        private var connected: JavaScriptSandbox? = null

        /**
         * 64 MB. Generous for anything a macro does with a value or an HTTP
         * body, and far below the point where the isolate would be competing
         * with the foreground app for memory.
         */
        const val MAX_HEAP_BYTES = 64L * 1024 * 1024

        const val TAG = "Ottomatic"

        /**
         * The sandbox, connecting it on first use.
         *
         * A failure is logged rather than swallowed. It surfaces to the user as
         * "this device cannot run scripts", which is the right thing to *show*
         * and a hopeless thing to diagnose from — the reason the bind failed
         * exists only here.
         */
        suspend fun connect(context: Context): JavaScriptSandbox? = connectMutex.withLock {
            connected ?: runCatching { JavaScriptSandbox.createConnectedInstanceAsync(context).await() }
                .onFailure { cause -> Log.w(TAG, "Could not connect a JavaScript sandbox", cause) }
                .getOrNull()
                ?.also { connected = it }
        }

        suspend fun forget(dead: JavaScriptSandbox) = connectMutex.withLock {
            if (connected === dead) {
                connected = null
                runCatching { dead.close() }
            }
        }

        fun closeSandbox() {
            connected?.let { runCatching { it.close() } }
            connected = null
        }
    }
}

/**
 * Collects `console` output from one isolate.
 *
 * Appended to from a Binder thread and read from the coroutine that started the
 * evaluation, so the list is synchronized and the callback body does nothing but
 * append — anything slower there delays the sandbox process.
 *
 * The cap matters more than it looks: `for (;;) console.log(i)` inside a two
 * second timeout emits tens of thousands of lines, and every one of them would
 * otherwise cross a process boundary into a bounded run log, evicting whatever
 * the user was actually trying to read. It belongs here rather than in the UI
 * for the same reason.
 */
private class ConsoleBuffer {

    private val lines = Collections.synchronizedList(mutableListOf<ScriptConsoleMessage>())
    private val suppressed = AtomicInteger()

    fun add(message: JavaScriptConsoleCallback.ConsoleMessage) {
        if (lines.size >= MAX_CONSOLE_LINES) {
            suppressed.incrementAndGet()
            return
        }
        lines += ScriptConsoleMessage(levelOf(message.level), message.message)
    }

    fun snapshot(): List<ScriptConsoleMessage> {
        val captured = synchronized(lines) { lines.toList() }
        val dropped = suppressed.get()
        if (dropped == 0) return captured
        return captured + ScriptConsoleMessage(
            level = ScriptConsoleLevel.WARNING,
            message = "… $dropped more console line(s) suppressed",
        )
    }

    private fun levelOf(level: Int): ScriptConsoleLevel = when (level) {
        JavaScriptConsoleCallback.ConsoleMessage.LEVEL_ERROR -> ScriptConsoleLevel.ERROR
        JavaScriptConsoleCallback.ConsoleMessage.LEVEL_WARNING -> ScriptConsoleLevel.WARNING
        JavaScriptConsoleCallback.ConsoleMessage.LEVEL_INFO -> ScriptConsoleLevel.INFO
        JavaScriptConsoleCallback.ConsoleMessage.LEVEL_DEBUG -> ScriptConsoleLevel.DEBUG
        else -> ScriptConsoleLevel.LOG
    }

    private companion object {
        const val MAX_CONSOLE_LINES = 100
    }
}

/**
 * Awaits a [ListenableFuture] without pulling in `kotlinx-coroutines-guava`.
 *
 * That artifact would have to be version-matched against a coroutines release
 * this project never declares (it arrives transitively through lifecycle and
 * Compose), and this is the only future in the codebase.
 *
 * Cancelling the awaiting coroutine cancels the future, which abandons the
 * *call*; ending the underlying work is the caller's `isolate.close()`.
 */
private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addListener(
        {
            // Unwrap ExecutionException so callers see the JavaScriptException
            // the library actually raised, message and all.
            continuation.resumeWith(
                runCatching { get() }.recoverCatching { cause ->
                    throw (cause as? ExecutionException)?.cause ?: cause
                },
            )
        },
        Runnable::run,
    )
    continuation.invokeOnCancellation { cancel(false) }
}
