package com.example.ottomatic.engine

import com.example.ottomatic.core.service.Contacts
import com.example.ottomatic.core.service.DeviceState
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.NoContacts
import com.example.ottomatic.core.service.LogSource
import com.example.ottomatic.core.service.MacroControl
import com.example.ottomatic.core.service.NoPrompts
import com.example.ottomatic.core.service.NoScripts
import com.example.ottomatic.core.service.NoVariables
import com.example.ottomatic.core.service.Prompts
import com.example.ottomatic.core.service.ScriptEngine
import com.example.ottomatic.core.service.Variables
import com.example.ottomatic.core.service.SystemServices
import com.example.ottomatic.core.service.UnknownDeviceState
import com.example.ottomatic.domain.model.PhoneRef
import com.example.ottomatic.engine.trigger.NoSensors
import com.example.ottomatic.engine.trigger.SensorReader

/**
 * Context available to an [Action] or [ValueNode] while executing.
 *
 * Provides access to shared infrastructure without pulling Android types
 * into `engine/`: logging, system services and (optionally) macro control.
 *
 * [systemServices] changes the device; [deviceState] and [sensors] read it.
 * Actions use the former, value nodes the latter two — and a value node may use
 * *only* the read-only pair, which is what its purity contract amounts to.
 *
 * [macroControl] is nullable: engine-only unit tests and environments without
 * a running [com.example.ottomatic.engine.service.MacroEngineService] may
 * leave it `null`; actions that require it should degrade gracefully.
 */
interface ExecutionContext {

    val systemServices: SystemServices

    /**
     * Read-only view of current device state, used by value nodes. Defaults to
     * [UnknownDeviceState] so engine-only tests need not supply one; a value node
     * then reads null, which contributes no item and fails a comparison closed.
     */
    val deviceState: DeviceState get() = UnknownDeviceState

    /**
     * One-shot sensor reads, used by the value nodes that pair with the sensor
     * triggers. Defaults to [NoSensors] for the same reason [deviceState]
     * defaults to unknown: an engine-only test reads null rather than inventing
     * a reading no sensor ever produced.
     */
    val sensors: SensorReader get() = NoSensors

    /**
     * Runs the JavaScript behind `action.script`. Defaults to [NoScripts] so
     * engine-only tests need not stand up a WebView sandbox: they then see the
     * same [com.example.ottomatic.core.service.ScriptOutcome.Unavailable] a
     * device without a usable WebView reports, which the action already has to
     * handle.
     *
     * An action, never a value node — an evaluation is slow and failable, so it
     * is not something the pull side may do.
     */
    val scripts: ScriptEngine get() = NoScripts

    /**
     * Named values that outlive a single run — written by `action.set_variable`,
     * read by `value.variable`. Defaults to [NoVariables] so an engine-only test
     * reads null and a comparison over it fails closed.
     *
     * The one facade both sides may touch: reading a variable is cheap and
     * cannot fail, so the pull side may do it, and writing one is a side effect,
     * so only an action does.
     */
    val variables: Variables get() = NoVariables

    /**
     * The device's address book, for the nodes that take a phone number. Defaults
     * to [NoContacts] so an engine-only test resolves nothing and the action fails
     * closed rather than dialling a number no address book ever held.
     *
     * An action's, never a value node's: see [Contacts].
     */
    val contacts: Contacts get() = NoContacts

    /**
     * Puts a question to the user and waits for the answer — the dialog nodes.
     * Defaults to [NoPrompts] so engine-only tests need no renderer: they then
     * see the same [com.example.ottomatic.core.service.PromptAnswer.Unavailable]
     * a phone without the overlay permission reports, which the nodes already
     * have to handle.
     *
     * An action's, never a value node's: it waits for a human, which is the
     * opposite of the pull side's "cheap and cannot fail".
     */
    val prompts: Prompts get() = NoPrompts

    /** Optional handle to enable/disable other macros at runtime, or null. */
    val macroControl: MacroControl? get() = null

    /**
     * Writes one line to the workflow's console, at [level].
     *
     * [LogLevel.INFO] by default, which is what a node saying something
     * deliberate — `action.log`, a variable write, a script's `console.log` —
     * should be. Traces that exist for diagnosis rather than for reading belong
     * at [LogLevel.DEBUG]; the console hides those unless asked.
     */
    fun log(message: String, level: LogLevel = LogLevel.INFO)

    /**
     * This same context, stamping [source] onto everything logged through it.
     *
     * A copy rather than a mutable "current node" field, because the executor
     * recurses and trigger flows run concurrently — a field shared between them
     * would attribute a line to whichever node happened to set it last. It also
     * means an action needs to know nothing about logging: the executor hands it
     * a context that is already stamped, and the action's plain `log("…")` picks
     * up its node for free.
     *
     * [source] carries the whole attribution at once on purpose. Splitting it
     * into a workflow call and a node call would break under class delegation:
     * the forwarder generated for the second would delegate to the *unscoped*
     * context and quietly discard the first.
     *
     * Defaults to `this`, so an engine-only test needs no attribution machinery.
     */
    fun scoped(source: LogSource): ExecutionContext = this
}

/**
 * The number [spec] means right now: a literal as it stands, a contact resolved
 * through the address book, and null when nothing is chosen or the contact cannot
 * be reached.
 *
 * Shared by `action.call` and `action.send_sms` so the rule is written once.
 *
 * It runs *after* [com.example.ottomatic.domain.registry.NodeSchema.decode] has
 * already resolved wire over form, so it sees whichever won — which means a wired
 * plain number behaves exactly as it always did, and a `contact:` spec that arrived
 * over a wire (read out of a variable, say) resolves too. That second case is a
 * deliberate consequence rather than an accident: a reference is a reference
 * wherever it came from.
 */
internal fun ExecutionContext.resolvePhone(spec: String): String? = when (val ref = PhoneRef.parse(spec)) {
    null -> null
    is PhoneRef.Literal -> ref.number
    is PhoneRef.Contact -> contacts.phoneNumber(ref.lookupKey)
}
