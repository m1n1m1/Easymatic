package com.example.ottomatic.engine

import com.example.ottomatic.core.service.Ai
import com.example.ottomatic.core.service.Calendars
import com.example.ottomatic.core.service.Files
import com.example.ottomatic.core.service.Images
import com.example.ottomatic.core.service.Contacts
import com.example.ottomatic.core.service.DeviceState
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.core.service.NoAi
import com.example.ottomatic.core.service.NoCalendars
import com.example.ottomatic.core.service.NoFiles
import com.example.ottomatic.core.service.NoImages
import com.example.ottomatic.core.service.NoContacts
import com.example.ottomatic.core.service.LogSource
import com.example.ottomatic.core.service.MacroControl
import com.example.ottomatic.core.service.Mail
import com.example.ottomatic.core.service.Media
import com.example.ottomatic.core.service.Messaging
import com.example.ottomatic.core.service.Microphone
import com.example.ottomatic.core.service.NoMail
import com.example.ottomatic.core.service.NoMedia
import com.example.ottomatic.core.service.NoMessaging
import com.example.ottomatic.core.service.NoMicrophone
import com.example.ottomatic.core.service.NoSpeech
import com.example.ottomatic.core.service.Speech
import com.example.ottomatic.core.service.NoNotifications
import com.example.ottomatic.core.service.NoPrompts
import com.example.ottomatic.core.service.Notifications
import com.example.ottomatic.core.service.HomeAssistant
import com.example.ottomatic.core.service.Mqtt
import com.example.ottomatic.core.service.NoHomeAssistant
import com.example.ottomatic.core.service.NoMqtt
import com.example.ottomatic.core.service.NoSmartHome
import com.example.ottomatic.core.service.SmartHome
import com.example.ottomatic.core.service.NoScripts
import com.example.ottomatic.core.service.NoVariables
import com.example.ottomatic.core.service.Prompts
import com.example.ottomatic.core.service.ScriptEngine
import com.example.ottomatic.core.service.Variables
import com.example.ottomatic.core.service.SystemServices
import com.example.ottomatic.core.service.UnknownDeviceState
import com.example.ottomatic.core.service.DelayWaits
import com.example.ottomatic.core.service.Waits
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

    /**
     * Sleeps until a wall-clock moment — `action.delay` and `action.wait_until`.
     * Defaults to [DelayWaits] so engine-only tests need no alarm manager: they
     * then get a plain coroutine sleep, which `runTest`'s virtual clock skips.
     *
     * An action's, never a value node's, for the reason [prompts] is: it waits.
     */
    val waits: Waits get() = DelayWaits

    /**
     * Sends and reads email — the mail nodes. Defaults to [NoMail] so engine-only
     * tests need no SMTP server: they then see exactly what a phone with an empty
     * account library reports, which the nodes already have to handle.
     *
     * An action's, never a value node's, and this is the clearest case of that
     * rule in the whole interface: every call is a network round trip, so it is
     * both of the things the pull side may not be — slow and failable. "How many
     * unread do I have?" is `action.fetch_mail` feeding `transform.list_count`,
     * on the exec wire where the latency is visible.
     */
    val mail: Mail get() = NoMail

    /**
     * Answers a messenger through the notification it posted — `action.reply_message`
     * and `action.notification_action`. Defaults to [NoMessaging] so engine-only tests
     * need no notification listener: they then see exactly what a phone that has never
     * granted notification access reports, which the nodes already have to handle.
     *
     * An action's, never a value node's, and for a plainer reason than [mail]'s: it
     * reads nothing at all. Every member sends something on another app's behalf.
     */
    val messaging: Messaging get() = NoMessaging

    /**
     * Posts this app's own notifications, and waits for what is done with them —
     * `action.notify` and `action.notify_cancel`. Defaults to [NoNotifications], so an
     * engine-only test sees exactly what a phone with notifications switched off does:
     * nothing posted, nothing to wait for.
     *
     * **The other half of [messaging], not a duplicate of it.** That one reads and
     * presses notifications *other apps* posted, over notification access; this one
     * posts Ottomatic's own, over no permission the user has to hunt for. They address
     * different things too — a tag this app chose, against a
     * [com.example.ottomatic.domain.model.ConversationRef] somebody else's app minted —
     * so nothing either facade holds is meaningful to the other.
     *
     * An action's, never a value node's, for [prompts]' reason rather than [mail]'s:
     * posting is cheap, but the member that matters waits for a human.
     */
    val notifications: Notifications get() = NoNotifications

    /**
     * Controls lights and other smart-home devices — the light nodes. Defaults to
     * [NoSmartHome] so engine-only tests need no bridge: they then see exactly what
     * a phone with an empty hub library reports, which the nodes already have to
     * handle.
     *
     * An action's, never a value node's, for the reason [mail] is and with the same
     * proof: every call is a network round trip, so it is both of the things the
     * pull side may not be. "Is the hall light on?" is `action.light_state` feeding
     * an `action.if`, on the exec wire where the latency is visible.
     */
    val smartHome: SmartHome get() = NoSmartHome

    /**
     * Home Assistant — `action.ha_service` and `value.ha_state`. Defaults to
     * [NoHomeAssistant] so engine-only tests need no server.
     *
     * **The first facade both sides of the graph may touch**, besides [variables], and
     * the exception is real rather than a relaxation of the rule. [smartHome], [mail]
     * and [ai] are actions' facades *because every member is a network round trip*;
     * here `state` is a map lookup into a cache a websocket keeps warm, so it is cheap,
     * repeatable and cannot fail — which is what the pull side actually requires. The
     * bar was never "must not concern the network"; it was "must be cheap and must not
     * fail", and a push channel clears it where a request-response API cannot.
     *
     * `call` is still an action's alone.
     */
    val homeAssistant: HomeAssistant get() = NoHomeAssistant

    /**
     * An MQTT broker — `action.mqtt_publish` and `value.mqtt_topic`. Defaults to [NoMqtt]
     * so engine-only tests need no broker.
     *
     * [homeAssistant]'s split, reached by the same argument and settling it: `lastMessage`
     * is a lookup into a cache a subscription keeps warm, `publish` is a side effect. What
     * makes this the clean case is that MQTT has **no read operation at all** — a
     * subscriber is told values and remembers them — so there is no faster or slower way
     * to answer the question, and nothing for the cache to be an optimisation *of*.
     *
     * The app is a client here and never a broker: everything on this facade is something
     * said to, or heard from, a broker somebody else is running.
     */
    val mqtt: Mqtt get() = NoMqtt

    /**
     * Asks a language model something — `action.ai_prompt`. Defaults to [NoAi] so
     * engine-only tests need no key and no network: they then see exactly what a
     * phone that has never been given an API key reports, which the node already
     * has to handle.
     *
     * An action's, never a value node's, for the reason [mail] and [smartHome]
     * are, and with the same proof: every call is a network round trip, so it is
     * both of the things the pull side may not be — and this one also *bills* for
     * itself, which makes "read it again just in case" the wrong default in a way
     * it never was for a light.
     */
    val ai: Ai get() = NoAi

    /**
     * Reads and writes files — the six `action.file_*` nodes. Defaults to [NoFiles],
     * so engine-only tests see exactly what a phone that has granted no folder does.
     *
     * An action's facade, never a value node's, and here the proof is different from
     * [mail]'s and [ai]'s rather than the same: it is not that a filesystem is slow but
     * that **nothing pushes**. [homeAssistant] and [mqtt] earn a place on the pull side
     * because a socket keeps a map warm; a filesystem answers only when asked, and a
     * folder the user granted may be served by a cloud provider, so the cheapest
     * possible read is still a round trip that can fail. `action.file_info` into
     * `action.if` is the honest shape, on the exec wire where the wait is visible.
     */
    val files: Files get() = NoFiles

    /**
     * Finds, reads and changes the pictures on the phone — the six `action.image_*` nodes,
     * `trigger.image_saved` and `value.latest_image`. Defaults to [NoImages].
     *
     * **Reachable from the pull side, unlike [files], and the difference is not slowness.**
     * A filesystem answers only when asked and a granted folder may be served over a
     * network, which is why there is no `value.file_exists`. MediaStore is a local
     * provider that is always installed: [Images.latest] is one indexed cursor query with
     * no socket, no credential and no timeout — `value.calendar_busy`'s argument, and the
     * same road [homeAssistant] and [mqtt] reach the pull side by. Only that one member
     * qualifies; everything else here decodes bitmaps or asks the user for permission.
     */
    val images: Images get() = NoImages

    /**
     * Reads and writes the device's calendars — the three `action.calendar_*` nodes and
     * the two calendar values. Defaults to [NoCalendars], so engine-only tests see
     * exactly what a phone that has granted nothing does.
     *
     * **The third facade both sides of the graph may touch**, after [variables] and
     * [homeAssistant], and it is the one that shows what that exception was actually
     * about. [homeAssistant] earned its place with a websocket keeping a map warm, which
     * read at the time like "a push channel is the exception" — but the rule underneath
     * was always *cheap, and cannot fail*. This has no push channel at all and clears it
     * anyway: a calendar query is local IPC to a provider that is always installed, which
     * is the same class of read as `value.wifi_network`, not the same class as [files],
     * where a granted folder may be served over the network by a cloud provider.
     *
     * What the pull side does not get is the *reporting*: [Calendars.busyNow] and
     * [Calendars.nextStart] answer a bare null, so "nothing on" and "could not tell" look
     * alike there. Keeping those apart is the action side's job, on the exec wire.
     */
    val calendars: Calendars get() = NoCalendars

    /**
     * Records sound from the microphone — the three `action.record_*` nodes. Defaults to
     * [NoMicrophone], so engine-only tests see exactly what a phone with no microphone does.
     *
     * **The fourth facade both sides of the graph may touch, and the cheapest of them.**
     * [calendars] cleared the pull side with local IPC to a provider; the one member
     * `value.recording` reads does not even leave the process — [Microphone.isRecording] is
     * a flag this app set itself, so it is not merely cheap and unfailing but incapable of
     * being otherwise. Nothing else here qualifies: the other three members hold hardware
     * for seconds at a time, which is squarely an action's job.
     */
    val microphone: Microphone get() = NoMicrophone

    /**
     * Says things out loud and hears what is said back — `action.speak`, `action.speak_stop`
     * and `action.listen`. Defaults to [NoSpeech], so engine-only tests see exactly what a
     * phone with no voice does.
     *
     * **The sixth facade both sides of the graph may touch, and it reaches the pull side by
     * [microphone]'s road rather than [calendars]'.** The one member `value.speaking` reads
     * does not leave the process: [Speech.isSpeaking] is a flag this app set when it queued
     * the utterance, so "cheap, repeatable and cannot fail" is a description of what it is
     * rather than a judgement about it.
     *
     * Nothing else here qualifies, and [Speech.listen] is the clearest exclusion in the whole
     * set — it holds the microphone for as long as somebody keeps talking, which is not a
     * read at all. [Speech.speak] fails the same test twice over: it holds hardware, and it
     * is a side effect on the room.
     */
    val speech: Speech get() = NoSpeech

    /**
     * Plays, pauses and reads whatever media player is running — the two `action.media_*`
     * nodes, `trigger.media_playback` and the two media values. Defaults to [NoMedia], so
     * engine-only tests see exactly what a phone that has never granted notification access
     * does.
     *
     * **The fifth facade both sides of the graph may touch**, and it reaches the pull side
     * by [calendars]' road rather than [homeAssistant]'s. There is no socket and no warm
     * cache: [Media.nowPlaying] and [Media.isPlaying] are one synchronous binder call into
     * a system service that is always running, with no credential, no network and no
     * timeout. That is what "cheap, and cannot fail" asked for all along — a push channel
     * was one way of meeting it, never the requirement.
     *
     * [Media.control] and [Media.seek] stay actions' alone, for [smartHome]'s reason read
     * one step in: they are side effects on somebody else's app, and there is no
     * acknowledgement to wait for, so a value node doing one could not even report whether
     * it worked.
     */
    val media: Media get() = NoMedia

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
