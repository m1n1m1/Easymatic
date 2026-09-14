package io.github.m1n1m1.easymatic

import android.content.Context
import io.github.m1n1m1.easymatic.core.capabilities.CapabilityChecker
import io.github.m1n1m1.easymatic.core.permissions.PermissionChecker
import io.github.m1n1m1.easymatic.core.service.Calendars
import io.github.m1n1m1.easymatic.core.service.DeviceState
import io.github.m1n1m1.easymatic.core.service.MacroControl
import io.github.m1n1m1.easymatic.core.service.RunLog
import io.github.m1n1m1.easymatic.core.service.ScriptEngine
import io.github.m1n1m1.easymatic.core.service.SystemServices
import io.github.m1n1m1.easymatic.data.AiConnectionRepository
import io.github.m1n1m1.easymatic.data.AssistantSettingsRepository
import io.github.m1n1m1.easymatic.data.ai.AiModelCatalog
import io.github.m1n1m1.easymatic.data.ai.OnDeviceAi
import io.github.m1n1m1.easymatic.data.ai.OnDeviceSetup
import io.github.m1n1m1.easymatic.data.ai.RoutingAi
import io.github.m1n1m1.easymatic.data.ai.ondevice.MlKitAi
import io.github.m1n1m1.easymatic.data.translate.MlKitTranslation
import io.github.m1n1m1.easymatic.data.translate.TranslationSetup
import io.github.m1n1m1.easymatic.data.files.RoutingFiles
import io.github.m1n1m1.easymatic.data.accessibility.ScreenCapture
import io.github.m1n1m1.easymatic.data.camera.CameraCapture
import io.github.m1n1m1.easymatic.data.images.MediaImages
import io.github.m1n1m1.easymatic.domain.registry.AiConnections
import io.github.m1n1m1.easymatic.data.GeofencePlaceRepository
import io.github.m1n1m1.easymatic.data.MailAccountRepository
import io.github.m1n1m1.easymatic.data.NfcTagRepository
import io.github.m1n1m1.easymatic.data.SmartHomeHubRepository
import io.github.m1n1m1.easymatic.core.service.SmartHome
import io.github.m1n1m1.easymatic.core.service.HubLink
import io.github.m1n1m1.easymatic.data.homeassistant.AndroidHomeAssistant
import io.github.m1n1m1.easymatic.data.homeassistant.HaConnections
import io.github.m1n1m1.easymatic.data.mqtt.AndroidMqtt
import io.github.m1n1m1.easymatic.data.mqtt.MqttConnections
import io.github.m1n1m1.easymatic.data.homeassistant.HaVendor
import io.github.m1n1m1.easymatic.data.hue.HueVendor
import io.github.m1n1m1.easymatic.data.smarthome.RoutingSmartHome
import io.github.m1n1m1.easymatic.domain.model.SmartHomeKind
import io.github.m1n1m1.easymatic.data.smarthome.SmartHomeSetup
import io.github.m1n1m1.easymatic.data.security.KeystoreSecrets
import io.github.m1n1m1.easymatic.data.mail.AndroidMail
import io.github.m1n1m1.easymatic.data.mail.AndroidMailSecrets
import io.github.m1n1m1.easymatic.data.notification.AndroidMessaging
import io.github.m1n1m1.easymatic.data.notification.AndroidNotifications
import io.github.m1n1m1.easymatic.data.mail.MailRuntime
import io.github.m1n1m1.easymatic.data.mail.MailSeenStore
import io.github.m1n1m1.easymatic.data.GlobalVariableRepository
import io.github.m1n1m1.easymatic.data.MacroTransferRepository
import io.github.m1n1m1.easymatic.domain.registry.GlobalVariables
import io.github.m1n1m1.easymatic.domain.registry.HaCatalog
import io.github.m1n1m1.easymatic.domain.registry.MqttCatalog
import io.github.m1n1m1.easymatic.domain.registry.SmartHomeHubs
import io.github.m1n1m1.easymatic.domain.registry.CalendarDirectory
import io.github.m1n1m1.easymatic.domain.registry.DeviceCapabilities
import io.github.m1n1m1.easymatic.domain.registry.GrantedPrerequisites
import io.github.m1n1m1.easymatic.data.WorkflowRepository
import io.github.m1n1m1.easymatic.data.log.RunLogStore
import io.github.m1n1m1.easymatic.data.capabilities.AndroidCapabilityChecker
import io.github.m1n1m1.easymatic.data.permissions.AndroidPermissionChecker
import io.github.m1n1m1.easymatic.data.api.ApiCallerRepository
import io.github.m1n1m1.easymatic.data.api.ApiCallers
import io.github.m1n1m1.easymatic.data.plugin.PluginConnections
import io.github.m1n1m1.easymatic.data.plugin.PluginPackages
import io.github.m1n1m1.easymatic.data.plugin.PluginRegistry
import io.github.m1n1m1.easymatic.data.plugin.PluginRepository
import io.github.m1n1m1.easymatic.data.prompt.OverlayPrompts
import io.github.m1n1m1.easymatic.data.script.WebViewScriptEngine
import io.github.m1n1m1.easymatic.data.audio.AndroidMicrophone
import io.github.m1n1m1.easymatic.data.speech.AndroidSpeech
import io.github.m1n1m1.easymatic.data.media.AndroidMedia
import io.github.m1n1m1.easymatic.data.sensor.SensorBridge
import io.github.m1n1m1.easymatic.data.calendar.AndroidCalendars
import io.github.m1n1m1.easymatic.data.service.AndroidContacts
import io.github.m1n1m1.easymatic.data.service.AndroidDeviceState
import io.github.m1n1m1.easymatic.data.service.AndroidMacroControl
import io.github.m1n1m1.easymatic.data.service.AndroidSystemServices
import io.github.m1n1m1.easymatic.data.trigger.AndroidTriggerHost
import io.github.m1n1m1.easymatic.data.trigger.VariableStore
import io.github.m1n1m1.easymatic.data.wait.AndroidWaits
import io.github.m1n1m1.easymatic.engine.DefaultExecutionContext
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.service.MacroEngineService
import io.github.m1n1m1.easymatic.engine.trigger.TriggerHost
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Minimal manual dependency container (Hilt deferred). Initialised once from
 * [MainActivity] with the application context. Holds the single shared
 * instances of every infrastructure component.
 */
object ServiceLocator {

    /**
     * Process-lifetime scope for work that must outlive the component that
     * started it — chiefly the graph editor's final save, which runs from
     * `onCleared()` after `viewModelScope` has already been cancelled.
     *
     * The handler is what keeps a failure here a failure: [SupervisorJob] stops one
     * child cancelling its siblings but does nothing about the exception itself,
     * which would otherwise reach the thread's default handler and take the process
     * down over a save that could not write.
     */
    val appScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            android.util.Log.e("Easymatic", "App-scope coroutine failed", e)
        },
    )

    lateinit var workflowRepository: WorkflowRepository
        private set

    /**
     * Reads a macro out to a file and back in again, for the workflow list's
     * Export / Share / Import commands.
     *
     * Built after the four libraries it draws on rather than beside them, because it
     * holds all four: the graph plus the credential-free entries it points at are what
     * make an exported macro work on the other phone rather than arrive full of
     * dangling references.
     */
    lateinit var macroTransferRepository: MacroTransferRepository
        private set

    /** The geofence place library, shared by the editor UI and the trigger host. */
    lateinit var geofencePlaceRepository: GeofencePlaceRepository

    lateinit var nfcTagRepository: NfcTagRepository
        private set

    /**
     * The mail account library, shared by the accounts screen, every config picker
     * and the mail facade.
     *
     * The one library holding a secret, which is why it is constructed with a
     * [io.github.m1n1m1.easymatic.data.mail.MailSecrets] rather than a directory alone.
     */
    lateinit var mailAccountRepository: MailAccountRepository
        private set

    /**
     * The smart-home hub library, shared by the Smart home screen, both light
     * pickers and the smart-home facade.
     *
     * The second library holding a secret, and it seals it under its **own**
     * keystore alias rather than sharing the mail one: two features with one key is
     * two features that cannot be revoked separately, and the alias costs nothing.
     */
    lateinit var smartHomeHubRepository: SmartHomeHubRepository
        private set

    /**
     * Pairing, refreshing and re-trusting a hub — the editor's half, which no node
     * has any use for. Separate from the facade on `MailAccountsViewModel.folders`'
     * reasoning: nothing in a running graph pairs a bridge.
     */
    lateinit var smartHomeSetup: SmartHomeSetup
        private set

    /**
     * The lights facade, and the one place a smart-home vendor is named.
     *
     * One instance per vendor, built once and shared by every hub of that kind: they
     * hold no per-hub state, resolving the hub and its credential on each call, so a
     * second household bridge costs nothing here. [RoutingSmartHome] picks from this
     * map by `hub.kind` and knows nothing else about any of them.
     *
     * Held here as well as inside [executionContext] because it must be **one**
     * instance: a second would keep a second set of per-hub gates, and the whole point
     * of those is that two commands to one bridge are ordered.
     *
     * `by lazy` rather than assigned in [init] so it is built after
     * [smartHomeHubRepository] whatever order [init] grows into — first touched when
     * the execution context is assembled, which is later in that same function.
     */
    /**
     * The Home Assistant push connections, and the cache they keep warm.
     *
     * One object serving three callers with three needs — the facade reads its cache,
     * the trigger host registers armed nodes with it, and [MacroEngineService] starts
     * and stops it through [HubLink] — because all three are about the same sockets.
     *
     * Given [appScope] rather than the service's scope: the service stops itself
     * whenever nothing is armed, and a connection torn down then would leave every
     * `value.ha_state` reading null in a macro that has no Home Assistant trigger in it.
     * The process is the honest owner, which is [MacroEngineService.runManual]'s
     * reasoning for its deferred-branch scope.
     */
    private val haConnections: HaConnections by lazy { HaConnections(smartHomeHubRepository, appScope) }

    /**
     * The MQTT broker connections, and the cache they keep warm.
     *
     * [haConnections]' twin in every respect including the scope, and it is held for the
     * engine's lifetime for the identical reason: the connection also keeps the cache
     * `value.mqtt_topic` reads, so refcounting it against armed triggers would leave that
     * node answering null in every macro that has no MQTT *trigger* in it.
     */
    private val mqttConnections: MqttConnections by lazy { MqttConnections(smartHomeHubRepository, appScope) }

    /**
     * The push connections, as the engine's service sees them.
     *
     * **Two managers behind one interface**, which is what [HubLink] being about a
     * *lifetime* rather than about a protocol buys: [MacroEngineService] starts and stops
     * "whatever this phone holds open" without learning that there are two of them, and a
     * third vendor with a push channel joins this list and edits nothing in `engine/`.
     */
    val hubLink: HubLink = object : HubLink {
        override fun start() {
            haConnections.start()
            mqttConnections.start()
        }

        override fun stop() {
            haConnections.stop()
            mqttConnections.stop()
        }
    }

    private val smartHomeFacade: SmartHome by lazy {
        RoutingSmartHome(
            smartHomeHubRepository,
            mapOf(
                SmartHomeKind.HUE to HueVendor(smartHomeHubRepository),
                SmartHomeKind.HOME_ASSISTANT to HaVendor(smartHomeHubRepository),
            ),
        )
    }

    /**
     * The global variable declarations, shared by the globals screen, every config
     * picker and the legacy repair. Its contents are also published to
     * [GlobalVariables], which is how `domain` resolves a global reference.
     */
    lateinit var globalVariableRepository: GlobalVariableRepository
        private set

    /**
     * The AI connection library, shared by the AI screen, the Ask AI node's picker
     * and the model facade.
     *
     * The third library holding a secret, sealed under its **own** keystore alias
     * for the reason the hub library has one: two features with one key is two
     * features that cannot be revoked separately, and the alias costs nothing.
     */
    lateinit var aiConnectionRepository: AiConnectionRepository
        private set

    /**
     * Lists the models a connection can reach, for the editor's chooser.
     *
     * A second class over the same repository holding the *editor's* needs and no
     * node's, exactly as [smartHomeSetup] is — no macro ever lists models, and the
     * facade the engine can see should not gain a method it must never call.
     */
    lateinit var aiModelCatalog: AiModelCatalog
        private set

    /**
     * The AI facade — `Ai` to the engine, the concrete class to the AI settings screen,
     * whose Test button needs the one member the interface must not carry.
     */
    lateinit var routingAi: RoutingAi
        private set

    /**
     * The model that answers on the phone itself, for the on-device AI provider.
     *
     * Shared by [aiModelCatalog], by the `RoutingAi` the engine reaches and by the AI
     * settings screen, which is what makes it a field here rather than three
     * constructions: it caches whether this phone can run the model at all, and three
     * copies of that cache would be three answers to a question the phone has one answer
     * to. The screen also needs the two members no macro ever calls — the download and
     * the model name — which is why it is reached directly rather than through
     * [io.github.m1n1m1.easymatic.core.service.Ai].
     */
    internal lateinit var onDeviceAi: OnDeviceAi
        private set

    /**
     * The on-device model's status, name and download, for the AI settings screen.
     *
     * A second class over [onDeviceAi] holding the editor's needs and no node's, exactly
     * as [aiModelCatalog] is over the connection library — and the reason is sharper here
     * than there: the member it carries starts a multi-gigabyte download, which is the
     * one thing an unattended macro must never be able to do.
     */
    lateinit var onDeviceSetup: OnDeviceSetup
        private set

    /**
     * The translator, for the engine, and the same object for the models screen.
     *
     * **One instance, two interfaces**, which is [onDeviceAi] and [onDeviceSetup]'s arrangement
     * arrived at from the other direction: there the split is two classes over one engine, here
     * it is two interfaces on one class. Either way the point is that a translator cache and a
     * models list are one fact about the phone, so the screen that deletes a model is talking to
     * the very object holding the clients that have it open.
     *
     * Typed as the setup half here because that is what has a caller outside the engine; the
     * execution context takes the same object as `Translation`, which carries no model
     * management at all.
     */
    lateinit var translationSetup: TranslationSetup
        private set

    /**
     * Which model the graph assistant asks, remembered between sessions.
     *
     * A preference about the editor rather than a fifth library: it holds one id and no
     * secret, so it is deliberately not part of [aiConnectionRepository] — a connection
     * library is credentials and the ways of asking through them, and "what the editor
     * used last" is neither.
     */
    lateinit var assistantSettingsRepository: AssistantSettingsRepository
        private set

    lateinit var systemServices: SystemServices
        private set

    lateinit var deviceState: DeviceState
        private set

    /**
     * The calendar provider, published because the **editor** needs it and no
     * `ExecutionContext` is in scope there: the calendar picker enumerates calendars, and
     * that is the one place in the app that does.
     *
     * `SmartHomeSetup`'s reasoning, arrived at from the other side — there a second class
     * exists so the editor's needs stay out of the nodes' facade, and here the editor
     * wants exactly one member the nodes already have, so a second class would be a
     * wrapper around one call.
     */
    lateinit var calendars: Calendars
        private set

    lateinit var macroControl: MacroControl
        private set

    /**
     * The JavaScript engine behind `action.script`. One instance per process,
     * because the platform allows exactly one sandbox and throws on a second.
     */
    lateinit var scriptEngine: ScriptEngine
        private set

    /**
     * The run log behind every workflow's console.
     *
     * One store for the process, which is what makes a background run visible in
     * the editor: [MacroEngineService][io.github.m1n1m1.easymatic.engine.service.MacroEngineService]
     * and the editor's preview run already share one [executionContext], and its
     * logger writes here. That holds only while both live in the same process —
     * giving the service an `android:process` would silently empty the console.
     */
    lateinit var runLog: RunLog
        private set

    lateinit var executionContext: ExecutionContext
        private set

    lateinit var triggerHost: TriggerHost
        private set

    /**
     * Application-context-backed permission checker. Cannot report
     * `showRationale` (needs an Activity) — fine for status checks but for the
     * actual request flow use an Activity-backed checker in `MainActivity`.
     */
    lateinit var permissionChecker: PermissionChecker

    /**
     * The hardware half of the same question, kept separate for the reason
     * `DeviceCapability` gives: a permission is something the user can grant, a
     * capability is a fact about the phone, and they reach different screens.
     */
    lateinit var capabilityChecker: CapabilityChecker
        private set

    /** Which plugin apps the user has enabled, and under which signer. */
    lateinit var pluginRepository: PluginRepository
        private set

    /** One refcounted binding per plugin package, kept warm between calls. */
    lateinit var pluginConnections: PluginConnections
        private set

    /**
     * Discovery, validation and publication of plugin nodes.
     *
     * The Plugins screen's own needs — enable, disable, refresh — live here rather
     * than on the repository, which is `SmartHomeSetup`'s and `AiModelCatalog`'s
     * arrangement for their libraries: a second class over the same store holding the
     * *editor's* needs, which no node has.
     */
    lateinit var pluginRegistry: PluginRegistry
        private set

    /**
     * Everything either trust gate asks `PackageManager`.
     *
     * One instance rather than two, because the *signer* reading is what both the
     * plugin enable and the API approval are pinned on, and two of them would be two
     * chances for the digest to be computed differently.
     */
    lateinit var packages: PluginPackages
        private set

    /** Which apps the user has allowed to call the process API, and under which signer. */
    lateinit var apiCallerRepository: ApiCallerRepository
        private set

    /**
     * Whether a calling app may use the process API.
     *
     * Constructed eagerly, unlike [pluginRegistry]'s asynchronous discovery, because
     * its first caller is a `ContentProvider` — which the system may spin this process
     * up expressly to serve, and which cannot wait for a hydration flag.
     */
    lateinit var apiCallers: ApiCallers
        private set

    /**
     * The application context, for the few callers that need `Resources` and are
     * reached from neither a composition nor an injected constructor — `MacroSnapshots`
     * builds widget labels from a background coroutine and is the reason this exists.
     * Always the *application* context, so nothing here can hold an Activity.
     */
    lateinit var appContext: Context
        private set

    /**
     * The same context, or null before [init] has run.
     *
     * Exists for code that is exercised by the JVM unit tests, which never call [init]
     * and have no Android at all. Reading the `lateinit` there would throw; answering
     * null lets the caller fall back to the declaration's English, which is exactly
     * what those tests are asserting about anyway.
     */
    val appContextOrNull: Context? get() = if (::appContext.isInitialized) appContext else null

    // A flat wiring list rather than branching logic: every line names one facade and
    // hands it its dependencies, so splitting it would only scatter the one place
    // somebody looks to find out what is plugged into what.
    @Suppress("LongMethod")
    fun init(context: Context) {
        appContext = context.applicationContext
        // Named rather than inlined because two facades share it: `files` is the
        // facade itself, and `images` borrows its opener so a picture outside the
        // media collection is still readable through one reading of a path.
        val routingFiles = RoutingFiles(appContext)
        // Published before anything else touches a workflow: `effectivePorts` and
        // `GraphValidator` resolve a global reference through this, and both run
        // from paths that can neither suspend nor be injected into.
        globalVariableRepository = GlobalVariableRepository(appContext.filesDir)
        GlobalVariables.hydrate(globalVariableRepository.list())
        workflowRepository = WorkflowRepository(appContext.filesDir, globalVariableRepository)
        geofencePlaceRepository = GeofencePlaceRepository(appContext.filesDir)
        nfcTagRepository = NfcTagRepository(appContext.filesDir)
        macroTransferRepository = MacroTransferRepository(
            workflows = workflowRepository,
            globals = globalVariableRepository,
            places = geofencePlaceRepository,
            nfcTags = nfcTagRepository,
            appVersion = appVersionOf(appContext),
        )
        // Keystore-backed, and safe to build here for exactly one reason: it never
        // throws. Both of its members answer null on failure, so an OEM keystore
        // that misbehaves costs the user a re-typed password rather than taking
        // Application.onCreate — and the whole app — down with it.
        mailAccountRepository = MailAccountRepository(appContext.filesDir, AndroidMailSecrets())
        // Its own keystore alias, so revoking one feature's stored credential never
        // touches the other's.
        smartHomeHubRepository = SmartHomeHubRepository(
            appContext.filesDir,
            KeystoreSecrets(SMART_HOME_KEY_ALIAS),
        )
        smartHomeSetup = SmartHomeSetup(smartHomeHubRepository, haConnections, mqttConnections)
        // Its own keystore alias too, so revoking the AI key never touches mail or
        // a paired bridge — and so an AI key, which the user can regenerate in a
        // browser in ten seconds, is never the reason a light stops working.
        aiConnectionRepository = AiConnectionRepository(appContext.filesDir, KeystoreSecrets(AI_KEY_ALIAS))
        // No context: `Generation.getClient` takes none, and ML Kit initialises itself
        // from its own manifest provider.
        onDeviceAi = MlKitAi()
        aiModelCatalog = AiModelCatalog(aiConnectionRepository, onDeviceAi)
        // Resolves the connection on every call, so a key pasted in or revoked mid-run
        // takes effect on the next prompt; one instance serves every connection.
        routingAi = RoutingAi(aiConnectionRepository, onDeviceAi)
        onDeviceSetup = OnDeviceSetup(onDeviceAi)
        // No context either, for `MlKitAi`'s reason. Constructing it publishes the *supported*
        // languages, which is a constant of the library.
        val translation = MlKitTranslation()
        translationSetup = translation
        // The *downloaded* half has to be asked for, and it is asked for here rather than only by
        // the Translation models screen: the Translate node's language pickers offer what is
        // downloaded, so a user who has never opened that screen would otherwise find both fields
        // empty with nothing saying why. Fire-and-forget on `appScope` because nothing waits on
        // it — `installed()` republishes `TranslateLanguages` as its side effect, and until it
        // returns the registry answers empty, which narrows nothing.
        appScope.launch { translation.installed() }
        assistantSettingsRepository = AssistantSettingsRepository(appContext.filesDir)
        publishAiConnections()
        publishSmartHomeHubs()
        systemServices = AndroidSystemServices(appContext)
        deviceState = AndroidDeviceState(appContext)
        macroControl = buildMacroControl(appContext)
        // Connects to the WebView sandbox lazily, on the first script a macro
        // runs — a process spawn is not something a user who never scripts
        // should pay for at startup.
        scriptEngine = WebViewScriptEngine(appContext)
        // Loads persisted variables now, before any macro can be armed: a
        // `value.variable` may be pulled the moment the engine starts, and a
        // counter that briefly read as unset would restart from zero.
        VariableStore.attach(appContext.filesDir, appScope)
        // One bridge for both sides of the sensors: the triggers that subscribe
        // to them through the host, and the value nodes that read one sample
        // through the context. Two would mean two platform registrations.
        val sensorBridge = SensorBridge(appContext)
        // One recorder for the process, and that is the whole of "there is one microphone":
        // an `action.record_stop` in one macro ends the recording an `action.record_start`
        // in another began, and `value.recording` reads the same flag both of them set. It
        // takes the routing files for `MediaImages`' reason and `appScope` because a
        // recording started by one run outlives that run.
        val microphoneFacade = AndroidMicrophone(appContext, routingFiles::place, appScope)
        // One voice for the process, and one pair of ears. A `TextToSpeech` is a connection
        // to another app that announces itself asynchronously, so a second instance would
        // orphan the first's progress listener and strand every utterance waiting on it;
        // `value.speaking` also has to read the same flag `action.speak` set, which two
        // instances could not agree on. It takes `appScope` for `AndroidMicrophone`'s reason,
        // one step further: an utterance queued without waiting outlives its whole run.
        val speechFacade = AndroidSpeech(appContext, appScope)
        val log = RunLogStore().apply { attach(appContext.filesDir, appScope) }
        runLog = log
        // One address book for the process, so an action resolving a contact and a
        // trigger matching against one cannot disagree about the same person.
        val contacts = AndroidContacts(appContext)
        // One renderer for the process, which is what makes "one dialog at a time"
        // true across macros rather than just within one: the mutex serialising it
        // lives on this instance.
        val prompts = OverlayPrompts(appContext)
        // One facade for the process, shared by the nodes and by the poll worker,
        // so an account edited in the app is the account the next check uses.
        val mailFacade = AndroidMail(mailAccountRepository)
        // One calendar reader for the process, passed to the trigger host as well rather
        // than rebuilt there — `AndroidContacts`' reason: an action reading an appointment
        // and a trigger planning an alarm against one must not disagree about it.
        //
        // Nothing is snapshotted here: the provider is asked on every call, on
        // `RoutingFiles`' reasoning and for the case that happens most, which is somebody
        // granting calendar access on the Permissions screen and running the macro from
        // the next screen along.
        val calendarsFacade = AndroidCalendars(appContext)
        calendars = calendarsFacade
        executionContext = DefaultExecutionContext(
            systemServices = systemServices,
            deviceState = deviceState,
            macroControl = macroControl,
            sensors = sensorBridge,
            scripts = scriptEngine,
            variables = VariableStore,
            contacts = contacts,
            prompts = prompts,
            // Alarm-backed, so "wait until 07:00" means 07:00 rather than
            // "whenever the phone next woke up after 07:00".
            waits = AndroidWaits(appContext),
            // Resolves an account and its password through the library on every
            // call rather than holding either: an account edited mid-run must not
            // be sent from with the settings it had when the engine started.
            mail = mailFacade,
            // Reads its handle out of ActiveNotifications on every call, because the
            // handle is a live notification: one held from when the engine started
            // would be revoked long before a macro got round to using it.
            messaging = AndroidMessaging(appContext),
            // Posts this app's own notifications, which is the other half of the pair
            // above and needs none of its permissions. One instance is fine here where
            // the facades around it are resolved per call: there is nothing to resolve
            // — the channel and the manager are the same two objects for the life of
            // the process, and what varies (the tag) arrives with each request.
            notifications = AndroidNotifications(appContext),
            // Resolves its hub on every call for the same reason, and serialises its
            // commands per hub so a loop over twenty lights does not have half of
            // them dropped by the bridge without anything saying so. Which *vendor*
            // is a per-hub fact, so one instance serves every hub on the phone and
            // this line names none of them.
            smartHome = smartHomeFacade,
            // Reads the cache the push connections keep warm, and sends its service
            // calls over REST — so an action works in the seconds after a reconnect and
            // from the editor's preview run, where no socket is up at all.
            homeAssistant = AndroidHomeAssistant(smartHomeHubRepository, haConnections),
            // Publishes and reads over the one connection per broker. Unlike Home
            // Assistant there is no second road for the action to take — publishing *is*
            // the protocol — so the connection is opened on demand when it is not already
            // up, which is the same guarantee reached differently.
            mqtt = AndroidMqtt(smartHomeHubRepository, mqttConnections),
            // The same instance the AI settings screen tests through — see [routingAi].
            ai = routingAi,
            // Resolves the covering grant on every call, on `RoutingAi`'s reasoning
            // and for a case that happens more often: somebody grants a folder on the
            // Folder access screen and runs the macro from the next screen along, and
            // a snapshot taken at start-up would make that work only after a restart.
            // Both storage facades take it, for that one reason.
            files = routingFiles,
            // The camera is bound here rather than taking a Context of its own, on
            // `ScreenCapture::grab`'s arrangement: `data/images` goes on knowing how a
            // picture is *written* and nothing about where its pixels came from.
            images = MediaImages(
                appContext,
                routingFiles::openStream,
                ScreenCapture::grab,
                { shot -> CameraCapture.take(appContext, shot) },
            ),
            calendars = calendarsFacade,
            microphone = microphoneFacade,
            media = AndroidMedia(appContext),
            speech = speechFacade,
            translation = translation,
            // Both destinations, because they answer different questions: the
            // store is what a user reads in the console, Logcat is what survives
            // a crash and can be pulled off a device over a cable.
            logger = { entry ->
                log.record(entry)
                android.util.Log.i("Easymatic", entry.message)
            },
        )
        // Published for MailPollWorker, which WorkManager builds from a
        // (Context, WorkerParameters) constructor and nothing else — the same
        // problem VariableStore.attach solves the same way.
        MailRuntime.attach(mail = mailFacade, seen = MailSeenStore(appContext))
        triggerHost = buildTriggerHost(sensorBridge, contacts)
        publishGrantedPrerequisites(appContext)
        refreshCalendars()
        publishPluginNodes(appContext)
    }

    /**
     * Publishes what the phone has granted **and what it can physically do**, for
     * `GraphValidator`.
     *
     * It asks whether a node can actually do its job and runs from paths that can
     * neither suspend nor be injected into. Re-read on every return to the foreground
     * by `MainActivity`, since granting any of these means leaving the app for a
     * Settings page.
     *
     * Two registries rather than one because they answer different questions and reach
     * different screens: a grant belongs on the Permissions screen because it can be
     * fixed there, and missing hardware would only be a row that never goes green. See
     * `DeviceCapability`.
     */
    private fun publishGrantedPrerequisites(appContext: Context) {
        permissionChecker = AndroidPermissionChecker(appContext)
        GrantedPrerequisites.hydrateFrom(permissionChecker)
        capabilityChecker = AndroidCapabilityChecker(appContext)
        DeviceCapabilities.hydrateFrom(capabilityChecker)
        // Whether this phone has a voice can only be learned from an engine's init callback,
        // so the first answer above is UNKNOWN and this is what replaces it. The third
        // hydration site, after `MainActivity.onResume` and the accessibility service, and
        // it is here for the reason that one is there: the fact arrives from somewhere the
        // app cannot ask, on somebody else's schedule.
        AndroidSpeech.capabilitiesChanged = { DeviceCapabilities.hydrateFrom(capabilityChecker) }
    }

    /**
     * Publishes the phone's calendars into [CalendarDirectory], if they can be read.
     *
     * Asynchronous, on `publishSmartHomeHubs`' shape, because the read is a provider round
     * trip and the first frames after process start must not wait on one.
     *
     * **Only a successful read publishes anything**, which is the whole subtlety. Before
     * `READ_CALENDAR` is granted this answers an error and an empty list, and hydrating
     * *that* would flip `isHydrated` to true and make every calendar reference on the
     * device look deleted — on a fresh install, before the user has done anything wrong.
     * Empty and unasked are different states, and this is the line that keeps them so.
     *
     * Called at start-up and again from `MainActivity.onResume`, since the grant is given
     * outside the app — `GrantedPrerequisites`' reason exactly.
     */
    fun refreshCalendars() {
        if (!::calendars.isInitialized) return
        appScope.launch {
            val listing = calendars.calendars()
            if (listing.ok) CalendarDirectory.hydrate(listing.calendars)
        }
    }

    /**
     * Discovers plugin apps and publishes whatever the enabled ones contribute.
     *
     * Asynchronous, on `publishSmartHomeHubs`' shape, so the first frames after process
     * start carry no plugin nodes at all. That gap is what `PluginNodes.isHydrated`
     * names, and `GraphValidator` reads it rather than condemning every plugin node in
     * every macro on the device for the seconds before discovery finishes.
     */
    /**
     * Builds the process API's trust gate, eagerly.
     *
     * Unlike [publishPluginNodes]' asynchronous discovery, this cannot be deferred:
     * `ApiTriggerProvider` is a `ContentProvider`, so the system may create this whole
     * process purely to serve one call. There is no screen to wait for and no
     * hydration flag a binder thread could read, so these have to exist by the time
     * `init` returns — which is affordable because all three constructors are a file
     * read and a `PackageManager` handle.
     */
    private fun publishApiCallers(appContext: Context) {
        packages = PluginPackages(appContext)
        apiCallerRepository = ApiCallerRepository(appContext.filesDir)
        apiCallers = ApiCallers(packages, apiCallerRepository)
    }

    private fun publishPluginNodes(appContext: Context) {
        // The two trust gates share `PluginPackages`, and this one has to be built
        // synchronously — see [publishApiCallers]. Only `refresh()` below is deferred.
        publishApiCallers(appContext)
        pluginRepository = PluginRepository(appContext.filesDir)
        pluginConnections = PluginConnections(appContext)
        pluginRegistry = PluginRegistry(packages, pluginRepository, pluginConnections, appScope)
        // A reconnected binding is not a restored subscription: a plugin trigger's
        // registration died with its process, and the host-side flow is still open and
        // silent. Re-arming is what turns that silence back into a working trigger, and
        // it goes through the ordinary re-arm path rather than a bespoke one.
        pluginConnections.onConnectionChanged { packageName, connected ->
            if (connected) {
                pluginRegistry.onReconnected(packageName) {
                    MacroEngineService.start(appContext, MacroEngineService.ACTION_REARM_CHANGED)
                }
            }
        }
        pluginRegistry.refresh()
    }

    /**
     * The AndroidKeyStore alias the hub library seals its application keys under.
     *
     * Carries its scheme version, as the mail one does, so a future rotation is a
     * parse branch rather than a schema bump.
     */
    private const val SMART_HOME_KEY_ALIAS = "easymatic.smarthome.v1"

    /**
     * Keeps [SmartHomeHubs] in step with the library, for `GraphValidator`, which
     * asks whether a light node's hub is still set up.
     *
     * Collected rather than hydrated once, because pairing and removing a hub both
     * happen long after startup — and a node pointing at a removed hub is the case
     * this exists for.
     */
    /**
     * The trigger host, and every library a trigger resolves something against.
     *
     * Extracted from [init] rather than inlined because it is a list that grows once per
     * integration, and a constructor call is not what [init] is for reading.
     * [sensorBridge] and [contacts] are passed rather than rebuilt so an armed trigger
     * and a value node share one platform registration between them.
     */
    private fun buildTriggerHost(sensorBridge: SensorBridge, contacts: AndroidContacts): TriggerHost =
        AndroidTriggerHost(
            appContext,
            geofencePlaceRepository,
            nfcTagRepository,
            sensorBridge,
            contacts,
            mailAccountRepository,
            smartHomeHubRepository,
            haConnections,
            mqttConnections,
            calendars,
        )

    private fun publishSmartHomeHubs() {
        appScope.launch {
            smartHomeHubRepository.hubs.collect { hubs ->
                SmartHomeHubs.hydrate(hubs.associate { it.id to it.resources })
                // A projection and never the hubs themselves: what a config form needs to
                // narrow a chooser, and nothing else. A hub also holds a sealed credential, an
                // address and a certificate pin, and a registry anything in `domain` may read
                // is no place for any of them.
                HaCatalog.hydrate(hubs.associate { it.id to (it.entities to it.services) })
                MqttCatalog.hydrate(hubs.associate { it.id to it.topics })
            }
        }
    }

    /**
     * Keeps [AiConnections] in step with the library, for `GraphValidator`, which
     * asks whether an AI node's connection still exists.
     *
     * Collected rather than hydrated once, on the hub library's reasoning:
     * connections are added and deleted long after startup, and a node pointing at
     * a deleted one is the case this exists for.
     */
    /**
     * The macro facade, whose execution context is passed as a **supplier**.
     *
     * The two are mutually dependent: [executionContext] is built below holding this,
     * and this needs that context to run a macro a tool asked for. A lambda is the
     * smaller of the two ways out — the other being a settable back-reference nothing
     * would stop being read before it was assigned.
     */
    private fun buildMacroControl(appContext: Context): MacroControl = AndroidMacroControl(
        context = appContext,
        repository = workflowRepository,
        executionContext = { executionContext },
        deferredScope = appScope,
    )

    /**
     * This build's version name, for the `appVersion` line an export file carries.
     *
     * Read from `PackageManager` rather than `BuildConfig` because the `buildConfig`
     * feature is not enabled in this module, and turning it on to stamp one advisory
     * string into a JSON file would add a generated class to every build. Purely
     * informational either way — nothing reads it back — so a blank on failure is the
     * right answer rather than a thrown one.
     */
    private fun appVersionOf(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private fun publishAiConnections() {
        appScope.launch {
            aiConnectionRepository.connections.collect(AiConnections::hydrateFrom)
        }
    }

    /**
     * The AndroidKeyStore alias the AI key is sealed under.
     *
     * Carries its scheme version, as the other two do, so a future rotation is a
     * parse branch rather than a schema bump.
     */
    private const val AI_KEY_ALIAS = "easymatic.ai.v1"
}
