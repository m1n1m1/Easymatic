package com.example.ottomatic

import android.content.Context
import com.example.ottomatic.core.permissions.PermissionChecker
import com.example.ottomatic.core.service.DeviceState
import com.example.ottomatic.core.service.MacroControl
import com.example.ottomatic.core.service.RunLog
import com.example.ottomatic.core.service.ScriptEngine
import com.example.ottomatic.core.service.SystemServices
import com.example.ottomatic.data.GeofencePlaceRepository
import com.example.ottomatic.data.MailAccountRepository
import com.example.ottomatic.data.NfcTagRepository
import com.example.ottomatic.data.mail.AndroidMail
import com.example.ottomatic.data.mail.AndroidMailSecrets
import com.example.ottomatic.data.GlobalVariableRepository
import com.example.ottomatic.domain.registry.GlobalVariables
import com.example.ottomatic.domain.registry.GrantedPrerequisites
import com.example.ottomatic.data.WorkflowRepository
import com.example.ottomatic.data.log.RunLogStore
import com.example.ottomatic.data.permissions.AndroidPermissionChecker
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
import com.example.ottomatic.engine.trigger.TriggerHost
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
     * The global variable declarations, shared by the globals screen, every config
     * picker and the legacy repair. Its contents are also published to
     * [GlobalVariables], which is how `domain` resolves a global reference.
     */
    lateinit var globalVariableRepository: GlobalVariableRepository
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

    fun init(context: Context) {
        val appContext = context.applicationContext
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
            mail = AndroidMail(mailAccountRepository),
            // Both destinations, because they answer different questions: the
            // store is what a user reads in the console, Logcat is what survives
            // a crash and can be pulled off a device over a cable.
            logger = { entry ->
                log.record(entry)
                android.util.Log.i("Ottomatic", entry.message)
            },
        )
        triggerHost =
            AndroidTriggerHost(appContext, geofencePlaceRepository, nfcTagRepository, sensorBridge, contacts)
        permissionChecker = AndroidPermissionChecker(appContext)
        // Published for `GraphValidator`, which asks whether a node can actually do
        // its job and runs from paths that can neither suspend nor be injected
        // into. Re-read on every return to the foreground by `MainActivity`, since
        // granting one of these means leaving the app for a Settings page.
        GrantedPrerequisites.hydrateFrom(permissionChecker)
    }
}
