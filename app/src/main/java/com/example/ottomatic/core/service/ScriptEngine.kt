package com.example.ottomatic.core.service

/**
 * Runs a piece of JavaScript and hands back what it evaluated to.
 *
 * The implementation is the V8 inside the device's system WebView, reached
 * through `androidx.javascriptengine`, so the code runs **in another process**
 * with no DOM, no network and no filesystem of its own. That isolation is the
 * point rather than a limitation: a script can only ever see the values
 * `action.script` marshals into it, so every side effect a macro has stays in
 * the actions on the canvas where it is visible.
 *
 * The contract is deliberately one-way in the direction that matters: values go
 * in, a result comes back, and a script cannot *invoke* anything on this side or
 * read a reply. The one channel out is [ScriptOutcome.console] — whatever the
 * script wrote with `console.log`, delivered with the outcome so the run log can
 * show it. That is observation, not capability; it lets a user debug a script
 * without letting a script do anything.
 *
 * This is a `core/` port with its Android half in `data/script/`, like
 * [SystemServices] — not a read-side facade like [DeviceState], because an
 * evaluation is slow, failable and may not be available at all.
 */
interface ScriptEngine {

    /**
     * Evaluates [source], which must itself evaluate to a JavaScript `String`;
     * anything else comes back from the platform as empty text.
     * `action.script` guarantees this by wrapping the user's code in a
     * `JSON.stringify`, which is also what makes [ScriptOutcome.Value] JSON.
     *
     * Never throws: every failure — a syntax error, a thrown exception, a
     * runaway loop hitting [timeoutMs], a device with no usable WebView — comes
     * back as an outcome, because the caller has to log it and carry on either
     * way.
     */
    suspend fun evaluate(source: String, timeoutMs: Long): ScriptOutcome
}

/** How loud a `console` call was, as the script wrote it. */
enum class ScriptConsoleLevel { DEBUG, LOG, INFO, WARNING, ERROR }

/**
 * One line a script wrote with `console.log` and friends.
 *
 * Deliberately no source, line or column. `action.script` inlines the whole user
 * script into one physical line of its wrapper, so the platform's line numbers
 * are offset by a template-dependent constant and its columns by the varying
 * length of the input bindings — a number that is confidently wrong is worse
 * than none.
 */
data class ScriptConsoleMessage(val level: ScriptConsoleLevel, val message: String)

/** What an evaluation produced. */
sealed interface ScriptOutcome {

    /**
     * What the script wrote to `console`, in the order it wrote it.
     *
     * Present on a failure too, and that is the point: a script that logs and
     * then loops forever reports only a timeout, and the line before the loop is
     * the only clue where it went.
     */
    val console: List<ScriptConsoleMessage> get() = emptyList()

    /** The script's return value, as the JSON text it stringified to. */
    data class Value(
        val json: String,
        override val console: List<ScriptConsoleMessage> = emptyList(),
    ) : ScriptOutcome

    /** The script ran and failed, or never finished. [message] is user-facing. */
    data class Error(
        val message: String,
        override val console: List<ScriptConsoleMessage> = emptyList(),
    ) : ScriptOutcome

    /**
     * This device cannot run scripts at all — no system WebView, or one too old
     * to provide a sandbox. Distinct from [Error] because it is a property of
     * the phone rather than of the script, so the message a macro logs should
     * not suggest the user's code is wrong.
     */
    data object Unavailable : ScriptOutcome
}

/**
 * An engine that runs nothing, for engine-only unit tests and previews.
 *
 * Reports [ScriptOutcome.Unavailable] rather than an empty success, so a test
 * that forgets to supply an engine fails the way a phone without WebView does
 * instead of silently producing zeroes — the same contract [UnknownDeviceState]
 * and [com.example.ottomatic.engine.trigger.NoSensors] keep for the read side.
 */
object NoScripts : ScriptEngine {
    override suspend fun evaluate(source: String, timeoutMs: Long): ScriptOutcome = ScriptOutcome.Unavailable
}
