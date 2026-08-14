package com.example.ottomatic

import android.content.Context
import com.example.ottomatic.core.permissions.PermissionChecker
import com.example.ottomatic.core.service.DeviceState
import com.example.ottomatic.core.service.MacroControl
import com.example.ottomatic.core.service.RunLog
import com.example.ottomatic.core.service.ScriptEngine
import com.example.ottomatic.core.service.SystemServices
import com.example.ottomatic.data.AiConnectionRepository
import com.example.ottomatic.data.ai.AiModelCatalog
import com.example.ottomatic.data.ai.RoutingAi
import com.example.ottomatic.domain.model.isConfigured
import com.example.ottomatic.domain.registry.AiConnections
import com.example.ottomatic.data.GeofencePlaceRepository
import com.example.ottomatic.data.MailAccountRepository
import com.example.ottomatic.data.NfcTagRepository
import com.example.ottomatic.data.SmartHomeHubRepository
import com.example.ottomatic.core.service.SmartHome
import com.example.ottomatic.data.homeassistant.HaVendor
import com.example.ottomatic.data.hue.HueVendor
import com.example.ottomatic.data.smarthome.RoutingSmartHome
import com.example.ottomatic.domain.model.SmartHomeKind
import com.example.ottomatic.data.smarthome.SmartHomeSetup
import com.example.ottomatic.data.security.KeystoreSecrets
import com.example.ottomatic.data.mail.AndroidMail
import com.example.ottomatic.data.mail.AndroidMailSecrets
import com.example.ottomatic.data.notification.AndroidMessaging
import com.example.ottomatic.data.mail.MailRuntime
import com.example.ottomatic.data.mail.MailSeenStore
import com.example.ottomatic.data.GlobalVariableRepository
import com.example.ottomatic.domain.registry.GlobalVariables
import com.example.ottomatic.domain.registry.SmartHomeHubs
import com.example.ottomatic.domain.registry.GrantedPrerequisites
import com.example.ottomatic.data.WorkflowRepository
import com.example.ottomatic.data.log.RunLogStore
import com.example.ottomatic.data.permissions.AndroidPermissionChecker
import com.example.ottomatic.data.api.ApiCallerRepository
import com.example.ottomatic.data.api.ApiCallers
import com.example.ottomatic.data.plugin.PluginConnections
import com.example.ottomatic.data.plugin.PluginPackages
import com.example.ottomatic.data.plugin.PluginRegistry
import com.example.ottomatic.data.plugin.PluginRepository
import com.example.ottomatic.data.prompt.OverlayPrompts
import com.example.ottomatic.data.script.WebViewScriptEngine
import com.example.ottomatic.data.sensor.SensorBridge
import com.example.ottomatic.data.service.AndroidContacts
import com.example.ottomatic.data.service.AndroidDeviceState
import com.example.ottomatic.data.service.AndroidMacroControl
import com.example.ottomatic.data.service.AndroidSystemServices
import com.example.ottomatic.data.trigger.AndroidTriggerHost
import com.example.ottomatic.data.trigger.VariableStore
import com.example.ottomatic.data.wait.AndroidWaits
import com.example.ottomatic.engine.DefaultExecutionContext
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.service.MacroEngineService
import com.example.ottomatic.engine.trigger.TriggerHost
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
            android.util.Log.e("Ottomatic", "App-scope coroutine failed", e)
        },
    )

    lateinit var workflowRepository: WorkflowRepository
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
     * [com.example.ottomatic.data.mail.MailSecrets] rather than a directory alone.
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

    lateinit var systemServices: SystemServices
        private set

    lateinit var deviceState: DeviceState
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
     * the editor: [MacroEngineService][com.example.ottomatic.engine.service.MacroEngineService]
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

    fun init(context: Context) {
        appContext = context.applicationContext
        // Published before anything else touches a workflow: `effectivePorts` and
        // `GraphValidator` resolve a global reference through this, and both run
        // from paths that can neither suspend nor be injected into.
        globalVariableRepository = GlobalVariableRepository(appContext.filesDir)
        GlobalVariables.hydrate(globalVariableRepository.list())
        workflowRepository = WorkflowRepository(appContext.filesDir, globalVariableRepository)
        geofencePlaceRepository = GeofencePlaceRepository(appContext.filesDir)
        nfcTagRepository = NfcTagRepository(appContext.filesDir)
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
        smartHomeSetup = SmartHomeSetup(smartHomeHubRepository)
        // Its own keystore alias too, so revoking the AI key never touches mail or
        // a paired bridge — and so an AI key, which the user can regenerate in a
        // browser in ten seconds, is never the reason a light stops working.
        aiConnectionRepository = AiConnectionRepository(appContext.filesDir, KeystoreSecrets(AI_KEY_ALIAS))
        aiModelCatalog = AiModelCatalog(aiConnectionRepository)
        publishAiConnections()
        publishSmartHomeHubs()
        systemServices = AndroidSystemServices(appContext)
        deviceState = AndroidDeviceState(appContext)
        macroControl = AndroidMacroControl(appContext)
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
            // Resolves its hub on every call for the same reason, and serialises its
            // commands per hub so a loop over twenty lights does not have half of
            // them dropped by the bridge without anything saying so. Which *vendor*
            // is a per-hub fact, so one instance serves every hub on the phone and
            // this line names none of them.
            smartHome = smartHomeFacade,
            // Resolves the connection on every call for the same reason: a key
            // pasted in mid-run must be the one the next prompt uses, and a revoked
            // one must stop working without waiting for the process to die. Which
            // *provider* is therefore also a per-call fact, so one instance serves
            // every connection on the phone and this line names none of them.
            ai = RoutingAi(aiConnectionRepository),
            // Both destinations, because they answer different questions: the
            // store is what a user reads in the console, Logcat is what survives
            // a crash and can be pulled off a device over a cable.
            logger = { entry ->
                log.record(entry)
                android.util.Log.i("Ottomatic", entry.message)
            },
        )
        // Published for MailPollWorker, which WorkManager builds from a
        // (Context, WorkerParameters) constructor and nothing else — the same
        // problem VariableStore.attach solves the same way.
        MailRuntime.attach(mail = mailFacade, seen = MailSeenStore(appContext))
        triggerHost = AndroidTriggerHost(
            appContext,
            geofencePlaceRepository,
            nfcTagRepository,
            sensorBridge,
            contacts,
            mailAccountRepository,
        )
        publishGrantedPrerequisites(appContext)
        publishPluginNodes(appContext)
    }

    /**
     * Publishes what the phone has granted, for `GraphValidator`.
     *
     * It asks whether a node can actually do its job and runs from paths that can
     * neither suspend nor be injected into. Re-read on every return to the foreground
     * by `MainActivity`, since granting any of these means leaving the app for a
     * Settings page.
     */
    private fun publishGrantedPrerequisites(appContext: Context) {
        permissionChecker = AndroidPermissionChecker(appContext)
        GrantedPrerequisites.hydrateFrom(permissionChecker)
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
    private const val SMART_HOME_KEY_ALIAS = "ottomatic.smarthome.v1"

    /**
     * Keeps [SmartHomeHubs] in step with the library, for `GraphValidator`, which
     * asks whether a light node's hub is still set up.
     *
     * Collected rather than hydrated once, because pairing and removing a hub both
     * happen long after startup — and a node pointing at a removed hub is the case
     * this exists for.
     */
    private fun publishSmartHomeHubs() {
        appScope.launch {
            smartHomeHubRepository.hubs.collect { hubs -> SmartHomeHubs.hydrate(hubs.map { it.id }) }
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
    private fun publishAiConnections() {
        appScope.launch {
            aiConnectionRepository.connections.collect { list ->
                AiConnections.hydrate(
                    connectionIds = list.map { it.id },
                    configuredIds = list.filter { it.isConfigured }.map { it.id },
                )
            }
        }
    }

    /**
     * The AndroidKeyStore alias the AI key is sealed under.
     *
     * Carries its scheme version, as the other two do, so a future rotation is a
     * parse branch rather than a schema bump.
     */
    private const val AI_KEY_ALIAS = "ottomatic.ai.v1"
}
