package io.github.m1n1m1.easymatic

import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.m1n1m1.easymatic.core.permissions.PrerequisiteType
import io.github.m1n1m1.easymatic.data.BootFailureStore
import io.github.m1n1m1.easymatic.data.location.AndroidLocationLookup
import io.github.m1n1m1.easymatic.domain.registry.DeviceCapabilities
import io.github.m1n1m1.easymatic.domain.registry.GrantedPrerequisites
import io.github.m1n1m1.easymatic.engine.service.MacroEngineService
import io.github.m1n1m1.easymatic.feature.ScreenTransitions
import io.github.m1n1m1.easymatic.feature.ai.AiConnectionsScreen
import io.github.m1n1m1.easymatic.feature.ai.AiConnectionsViewModel
import io.github.m1n1m1.easymatic.feature.geofence.GeofencePlacesScreen
import io.github.m1n1m1.easymatic.feature.geofence.GeofencePlacesViewModel
import io.github.m1n1m1.easymatic.feature.mail.MailAccountsScreen
import io.github.m1n1m1.easymatic.feature.translate.TranslationModelsScreen
import io.github.m1n1m1.easymatic.feature.translate.TranslationModelsViewModel
import io.github.m1n1m1.easymatic.feature.smarthome.SmartHomeScreen
import io.github.m1n1m1.easymatic.feature.smarthome.SmartHomeViewModel
import io.github.m1n1m1.easymatic.feature.mail.MailAccountsViewModel
import io.github.m1n1m1.easymatic.feature.nfc.NfcTagsScreen
import io.github.m1n1m1.easymatic.feature.nfc.NfcTagsViewModel
import io.github.m1n1m1.easymatic.feature.files.FolderAccessScreen
import io.github.m1n1m1.easymatic.feature.files.FolderAccessViewModel
import io.github.m1n1m1.easymatic.feature.grapheditor.GraphEditorScreen
import io.github.m1n1m1.easymatic.feature.grapheditor.GraphEditorViewModel
import io.github.m1n1m1.easymatic.feature.variables.GlobalVariablesScreen
import io.github.m1n1m1.easymatic.feature.permissions.BatteryOptimisationDialog
import io.github.m1n1m1.easymatic.feature.permissions.PermissionsScreen
import io.github.m1n1m1.easymatic.feature.api.ApiAccessScreen
import io.github.m1n1m1.easymatic.data.security.EscrowState
import io.github.m1n1m1.easymatic.feature.backup.BackupScreen
import io.github.m1n1m1.easymatic.feature.backup.BackupViewModel
import io.github.m1n1m1.easymatic.feature.backup.UnlockBackupDialog
import io.github.m1n1m1.easymatic.feature.plugins.PluginsScreen
import io.github.m1n1m1.easymatic.feature.language.LanguageScreen
import io.github.m1n1m1.easymatic.feature.setup.InfoScreen
import io.github.m1n1m1.easymatic.engine.api.listApiTriggers
import io.github.m1n1m1.easymatic.feature.variables.GlobalVariablesViewModel
import io.github.m1n1m1.easymatic.feature.home.HomeScreen
import io.github.m1n1m1.easymatic.feature.workflowlist.WorkflowListViewModel
import io.github.m1n1m1.easymatic.ui.theme.EasymaticTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private fun openDocumentation() {
        startActivity(Intent(this, io.github.m1n1m1.easymatic.feature.help.DocumentationActivity::class.java))
    }

    private val listViewModel: WorkflowListViewModel by viewModels {
        WorkflowListViewModel.factory(
            repository = ServiceLocator.workflowRepository,
            transfers = ServiceLocator.macroTransferRepository,
            runLog = ServiceLocator.runLog,
            appContext = applicationContext,
        )
    }

    // Activity-scoped, unlike the graph editor's: the place library is global,
    // so the standalone Geofences screen and every workflow's config picker
    // must see the same instance and the same in-flight edits.
    private val geofencePlacesViewModel: GeofencePlacesViewModel by viewModels {
        GeofencePlacesViewModel.factory(
            repository = ServiceLocator.geofencePlaceRepository,
            locationLookup = AndroidLocationLookup(applicationContext),
            appContext = applicationContext,
        )
    }

    // Activity-scoped for the same reason the place library is, and needing less
    // than either: the tag library has no asynchronous work behind it at all, only
    // a synchronous repository, so this exists purely so the Tags screen and every
    // config picker share one list and one open capture.
    private val nfcTagsViewModel: NfcTagsViewModel by viewModels {
        NfcTagsViewModel.factory(
            repository = ServiceLocator.nfcTagRepository,
            appContext = applicationContext,
        )
    }

    // Activity-scoped like the libraries above, and holding no repository at all:
    // the platform's own list of persisted grants is the list, so there is nothing
    // to keep in sync and nothing that could disagree with it.
    private val folderAccessViewModel: FolderAccessViewModel by viewModels {
        FolderAccessViewModel.factory(applicationContext)
    }

    // Activity-scoped like the rest. The plugin refresh is handed in rather than reached
    // for, so the ViewModel holds a repository and a callback and no locator.
    private val backupViewModel: BackupViewModel by viewModels {
        BackupViewModel.factory(
            backups = ServiceLocator.backupRepository,
            escrow = ServiceLocator.secretEscrow,
            afterRestore = { ServiceLocator.pluginRegistry.refresh() },
            appContext = applicationContext,
        )
    }

    // Raised in onResume when credentials that came back through Android's own restore
    // are waiting for the backup password. Once per process: "Later" means later, and
    // the Backup screen's card is the durable answer, as the Permissions row is for the
    // battery prompt.
    private var showUnlockPrompt by mutableStateOf(false)
    private var unlockPromptDismissed = false

    // Activity-scoped like the rest, and holding no repository for `folderAccessViewModel`'s
    // reason exactly: ML Kit's own list of downloaded models is the list.
    private val translationModelsViewModel: TranslationModelsViewModel by viewModels {
        TranslationModelsViewModel.Factory(ServiceLocator.translationSetup)
    }

    // Activity-scoped for the same reason the others are, and with one extra edge
    // this library has that none of them do: an account edited from inside a node's
    // picker has to be the same account the standalone screen is showing, because
    // "type the password again" is a fix somebody may reach for from either place.
    private val mailAccountsViewModel: MailAccountsViewModel by viewModels {
        MailAccountsViewModel.factory(
            repository = ServiceLocator.mailAccountRepository,
            appContext = applicationContext,
        )
    }

    // Activity-scoped for the same reason the mail library is, and with the same
    // edge: a bridge paired from inside a Control Light node's picker has to be the
    // hub the Smart home screen is showing, since "pair it again" is a fix somebody
    // may reach for from either place.
    private val smartHomeViewModel: SmartHomeViewModel by viewModels {
        SmartHomeViewModel.factory(
            repository = ServiceLocator.smartHomeHubRepository,
            setup = ServiceLocator.smartHomeSetup,
            appContext = applicationContext,
        )
    }

    // Activity-scoped for the mail library's reason, and with the same edge: a
    // connection added from inside an Ask AI node's picker has to be the connection
    // the standalone screen is showing, since "paste the key again" is a fix
    // somebody may reach for from either place.
    private val aiConnectionsViewModel: AiConnectionsViewModel by viewModels {
        AiConnectionsViewModel.factory(
            repository = ServiceLocator.aiConnectionRepository,
            ai = ServiceLocator.routingAi,
            catalog = ServiceLocator.aiModelCatalog,
            onDevice = ServiceLocator.onDeviceSetup,
            appContext = applicationContext,
            macroControl = ServiceLocator.macroControl,
        )
    }

    // Activity-scoped for the same reason the place library is: a global variable
    // is global, so the standalone screen and every open editor's picker must see
    // one instance or an edit in either would be invisible in the other.
    private val globalVariablesViewModel: GlobalVariablesViewModel by viewModels {
        GlobalVariablesViewModel.factory(
            repository = ServiceLocator.globalVariableRepository,
            appContext = applicationContext,
        )
    }

    // Set in onResume when BootFailureStore has a pending flag and a macro is
    // enabled. Rendered as a battery-optimisation dialog over whichever screen
    // is showing.
    private var showBatteryPrompt by mutableStateOf(false)

    // The result code is meaningless here — the dialog reports RESULT_CANCELED
    // whichever button was pressed — so the only way to know what happened is to
    // read the exemption back.
    private val requestIgnoreBatteryOptimizations =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
            if (isBatteryExempt()) {
                // It took. Bring the engine up now so this session matches what the
                // next reboot will manage on its own.
                MacroEngineService.start(this, MacroEngineService.ACTION_REARM_ALL)
            }
            // If it did not, the user declined, and pushing them somewhere else
            // would be arguing with an answer they just gave. The Permissions
            // screen is there whenever they change their mind.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // ServiceLocator is initialised in EasymaticApplication.onCreate(), which
        // runs before any Activity or manifest receiver, so it is ready here.
        super.onCreate(savedInstanceState)
        // The app uses a fixed dark palette, so force light system bar icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            EasymaticTheme(darkTheme = true, dynamicColor = false) {
                AppNavHost()
            }
        }
    }

    /**
     * The launcher's static "New macro" shortcut arrives here when the app is
     * already running.
     *
     * `onNewIntent` and not only `onCreate` because this Activity has no
     * `launchMode` — the default `standard` reuses the existing instance when the
     * task is already in front, and a shortcut tapped in that state would
     * otherwise be delivered to an intent nobody reads. `setIntent` is what makes
     * the composable below see it: [pendingNewMacro] reads `intent`, which
     * otherwise still holds the LAUNCHER intent from cold start.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_NEW_MACRO) newMacroRequests.tryEmit(Unit)
    }

    /**
     * The app's destinations: home (the workflow list and the Setup tab), the ten
     * screens Setup lists, and the graph editor.
     */
    /**
     * "New macro" taps that arrived while the app was already open.
     *
     * A flow rather than a state flag because the request is an *event*: tapping
     * the shortcut twice should make two macros, and a boolean that is already
     * true the second time would make one.
     */
    private val newMacroRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Composable
    private fun AppNavHost() {
        val navController = rememberNavController()

        // The cold-start half: the shortcut launched the app, so the intent is
        // already sitting on the Activity. Keyed on Unit so it runs once per
        // composition of the host rather than once per recomposition.
        LaunchedEffect(Unit) {
            if (intent?.action == ACTION_NEW_MACRO) {
                intent.action = null
                listViewModel.create { id -> navController.navigate("$ROUTE_GRAPH_EDITOR/$id") }
            }
            newMacroRequests.collect {
                listViewModel.create { id -> navController.navigate("$ROUTE_GRAPH_EDITOR/$id") }
            }
        }

        NavHost(
            navController = navController,
            startDestination = ROUTE_HOME,
            // Material's forward-and-backward pattern — see `ScreenTransitions`.
            // While the screen underneath dims, the window background shows
            // through it, which Theme.Easymatic pins to the canvas colour so
            // nothing flashes.
            enterTransition = { ScreenTransitions.forwardEnter },
            exitTransition = { ScreenTransitions.forwardExit },
            popEnterTransition = { ScreenTransitions.backEnter },
            popExitTransition = { ScreenTransitions.backExit },
            // The back *gesture* scrubs a pop of its own, and NavHost does not
            // default it to the pop above: left alone, a swipe shrinks the screen
            // to 70 % and fades where the arrow slides it aside, so the same
            // action looked like two. Naming the pop pair here is what makes the
            // arrow and the gesture one animation (`enableOnBackInvokedCallback`
            // in the manifest is what lets the gesture reach here at all).
            predictivePopEnterTransition = { ScreenTransitions.backEnter },
            predictivePopExitTransition = { ScreenTransitions.backExit },
        ) {
            composable(ROUTE_HOME) {
                HomeScreen(
                    onOpenHelp = ::openDocumentation,
                    listViewModel = listViewModel,
                    onOpenWorkflow = { id -> navController.navigate("$ROUTE_GRAPH_EDITOR/$id") },
                    onOpenSmartHome = { navController.navigate(ROUTE_SMART_HOME) },
                    onOpenAi = { navController.navigate(ROUTE_AI) },
                    onOpenMailAccounts = { navController.navigate(ROUTE_MAIL_ACCOUNTS) },
                    onOpenVariables = { navController.navigate(ROUTE_VARIABLES) },
                    onOpenGeofences = { navController.navigate(ROUTE_GEOFENCES) },
                    onOpenNfcTags = { navController.navigate(ROUTE_NFC_TAGS) },
                    onOpenFolders = { navController.navigate(ROUTE_FOLDER_ACCESS) },
                    onOpenTranslationModels = { navController.navigate(ROUTE_TRANSLATION_MODELS) },
                    onOpenPermissions = { navController.navigate(ROUTE_PERMISSIONS) },
                    onOpenPlugins = { navController.navigate(ROUTE_PLUGINS) },
                    onOpenAppAccess = { navController.navigate(ROUTE_APP_ACCESS) },
                    onOpenLanguage = { navController.navigate(ROUTE_LANGUAGE) },
                    onOpenBackup = { navController.navigate(ROUTE_BACKUP) },
                    onOpenInfo = { navController.navigate(ROUTE_INFO) },
                )
            }
            composable(ROUTE_GEOFENCES) {
                GeofencePlacesScreen(
                    viewModel = geofencePlacesViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(ROUTE_NFC_TAGS) {
                NfcTagsScreen(
                    viewModel = nfcTagsViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(ROUTE_FOLDER_ACCESS) {
                FolderAccessScreen(
                    viewModel = folderAccessViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(ROUTE_BACKUP) {
                BackupScreen(
                    viewModel = backupViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(ROUTE_TRANSLATION_MODELS) {
                TranslationModelsScreen(
                    viewModel = translationModelsViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(ROUTE_MAIL_ACCOUNTS) {
                MailAccountsScreen(
                    viewModel = mailAccountsViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(ROUTE_SMART_HOME) {
                SmartHomeScreen(
                    viewModel = smartHomeViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(ROUTE_AI) {
                AiConnectionsScreen(
                    viewModel = aiConnectionsViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(ROUTE_VARIABLES) {
                GlobalVariablesScreen(
                    viewModel = globalVariablesViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(ROUTE_PLUGINS) {
                // No ViewModel, on the Permissions screen's reasoning: the state is a
                // package-manager scan that has to be taken again on every entry anyway,
                // because plugins are installed and uninstalled outside this app.
                PluginsScreen(
                    registry = ServiceLocator.pluginRegistry,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(ROUTE_APP_ACCESS) {
                // No ViewModel either, on the same reasoning: the approvals are a
                // StateFlow the screen collects, and the one derived number — how many
                // macros are currently reachable — is a repository read that has to be
                // taken again on every entry, because a macro may have gained or lost
                // its trigger since.
                val reachable by produceState(initialValue = 0) {
                    value = listApiTriggers(ServiceLocator.workflowRepository).triggers.size
                }
                ApiAccessScreen(
                    callers = ServiceLocator.apiCallers,
                    reachableMacros = reachable,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(ROUTE_LANGUAGE) {
                LanguageScreen(onBack = { navController.popBackStack() })
            }
            composable(ROUTE_INFO) {
                InfoScreen(onBack = { navController.popBackStack() })
            }
            composable(ROUTE_PERMISSIONS) {
                // No ViewModel: the state is a handful of synchronous platform
                // reads that have to be taken again on every resume anyway, which
                // is the one thing a StateFlow would not give for free. The
                // checker is passed in so the screen needs no import from `data`.
                PermissionsScreen(
                    checker = ServiceLocator.permissionChecker,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = "$ROUTE_GRAPH_EDITOR/{$ARG_WORKFLOW_ID}",
                arguments = listOf(navArgument(ARG_WORKFLOW_ID) { type = NavType.StringType }),
            ) { backStackEntry ->
                val workflowId = backStackEntry.arguments?.getString(ARG_WORKFLOW_ID).orEmpty()
                if (workflowId.isBlank()) {
                    // No id to edit: an editor bound to "" would read and write
                    // workflows/.json. Bounce back to the list instead.
                    LaunchedEffect(Unit) { navController.popBackStack() }
                    return@composable
                }
                // Scoped to the NavBackStackEntry (the default owner for
                // viewModel() inside composable {}), NOT the Activity: each
                // workflow gets its own instance, cleared when the entry is
                // popped. An activity-scoped ViewModel would be created once
                // and keep serving the first workflow's graph — and save it
                // over every workflow opened afterwards.
                val editorViewModel: GraphEditorViewModel = viewModel(
                    key = workflowId,
                    factory = GraphEditorViewModel.factory(
                        repository = ServiceLocator.workflowRepository,
                        transfers = ServiceLocator.macroTransferRepository,
                        executionContext = ServiceLocator.executionContext,
                        runLog = ServiceLocator.runLog,
                        appContext = applicationContext,
                        appScope = ServiceLocator.appScope,
                        assistantSettings = ServiceLocator.assistantSettingsRepository,
                        workflowId = workflowId,
                        globalVariables = ServiceLocator.globalVariableRepository.variables,
                    ),
                )
                GraphEditorScreen(
                    onOpenHelp = ::openDocumentation,
                    viewModel = editorViewModel,
                    geofencePlaces = geofencePlacesViewModel,
                    nfcTags = nfcTagsViewModel,
                    mailAccounts = mailAccountsViewModel,
                    smartHome = smartHomeViewModel,
                    aiConnections = aiConnectionsViewModel,
                    globalVariables = globalVariablesViewModel,
                    translationModels = translationModelsViewModel,
                    // Leaves the editor rather than opening in place, unlike every other
                    // library the config form can reach — see `TranslationModelLibrary`.
                    onOpenTranslationModels = { navController.navigate(ROUTE_TRANSLATION_MODELS) },
                    onBack = { navController.popBackStack() },
                )
            }
        }

        // Outside the NavHost, because it is about the app rather than about
        // whichever destination happens to be showing. Inside the editor it could
        // only appear once the user opened a macro — which is both later than the
        // prompt was raised and somewhere that implied opening that macro had
        // caused it.
        if (showBatteryPrompt) {
                BatteryOptimisationDialog(
                    onDismiss = { showBatteryPrompt = false },
                    onConfirm = {
                        showBatteryPrompt = false
                        requestBatteryOptimizationExemption()
                    },
                )
        }
        // Same placement, same reasoning: an Android restore lands the user on whatever
        // screen they open first, and the locked credentials are about the app as a whole.
        if (showUnlockPrompt) {
            UnlockBackupDialog(
                body = stringResource(R.string.backup_unlock_prompt_body),
                onUnlock = { ServiceLocator.secretEscrow.unlock(it) },
                onDismiss = {
                    showUnlockPrompt = false
                    unlockPromptDismissed = true
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Every one of these is granted by leaving the app — a runtime dialog or a
        // Settings page — so coming back is exactly when the answer can have
        // changed, and is the only signal we get that it did. Cheap enough to
        // repeat: a handful of synchronous checks over the distinct grants the node
        // types declare.
        GrantedPrerequisites.hydrateFrom(ServiceLocator.permissionChecker)
        // The hardware half, re-read on the same signal and for a reason particular to
        // it: whether the fingerprint reader reports swipes can only be asked of a bound
        // accessibility service, so enabling that service in Settings is exactly when an
        // UNKNOWN can turn into a real answer.
        DeviceCapabilities.hydrateFrom(ServiceLocator.capabilityChecker)
        // The same signal, one layer out: calendar access is granted by leaving the app,
        // and until it lands there is no calendar the validator or the AI tool catalogue
        // can name. A read that fails publishes nothing rather than an empty list — see
        // CalendarDirectory.isHydrated for why those two must not be confused.
        ServiceLocator.refreshCalendars()
        // The third fact about the phone that only a trip outside the app can change:
        // AICore updates itself and downloads the on-device model on its own schedule, so
        // a "cannot run it" cached before that finished would outlive the truth. Dropping
        // the cache rather than re-reading it, because unlike the two above nothing is
        // waiting on the answer — the AI screen asks when it opens, and a macro asks when
        // it runs.
        ServiceLocator.onDeviceSetup.forget()
        // Credentials Android's own restore brought back are unreadable until the backup
        // password is typed; the first resume after that restore is where to ask.
        showUnlockPrompt = !unlockPromptDismissed &&
            ServiceLocator.secretEscrow.state.value is EscrowState.Locked
        // Surface the battery-optimisation prompt only when a background start
        // actually failed, at least one macro is enabled (no point prompting about
        // a failure to arm nothing), AND the exemption is not already in hand.
        //
        // That last condition is the one that was missing. The flag says only that
        // a boot start failed, which on Android 12+ and restricted OEM builds it
        // can do for reasons battery optimisation has nothing to do with — and it
        // is set again on every reboot. So an app that already had the exemption
        // was told to go and grant it, over and over, with the popup landing in
        // the editor because that was the only screen that drew it.
        //
        // Assigned rather than only set true, so a prompt raised while the answer
        // was still "no" is taken down again once it is "yes".
        lifecycleScope.launch {
            val needsPrompt = BootFailureStore.consume(this@MainActivity)
            showBatteryPrompt = needsPrompt &&
                !isBatteryExempt() &&
                ServiceLocator.workflowRepository.list().any { it.enabled }
        }
    }

    /**
     * Asks the system for the exemption, and makes sure the ask is one that can
     * actually be answered.
     *
     * The short-circuit is the load-bearing part: launching
     * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` when the app is *already*
     * exempt shows a dialog that grants nothing, or on some builds no dialog at
     * all, so "Allow" appeared to do nothing and the prompt came back. It should
     * never get this far now — [showBatteryPrompt] is gated on the same read —
     * but the button must not lie either way.
     *
     * The fallback covers the other way the ask can fail to appear: the activity
     * is optional, and a build without it used to throw straight out of
     * `launch()`. That is the one case where sending the user to the list page is
     * right, because they have not declined anything yet.
     */
    private fun requestBatteryOptimizationExemption() {
        if (isBatteryExempt()) {
            MacroEngineService.start(this, MacroEngineService.ACTION_REARM_ALL)
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = "package:$packageName".toUri()
        }
        runCatching { requestIgnoreBatteryOptimizations.launch(intent) }.onFailure {
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    /** Whether the app is currently exempt from battery optimisation. */
    private fun isBatteryExempt(): Boolean =
        ServiceLocator.permissionChecker
            .isPrerequisiteSatisfied(PrerequisiteType.BATTERY_OPTIMISATION)

    companion object {
        private const val ROUTE_HOME = "home"
        private const val ROUTE_GRAPH_EDITOR = "graphEditor"
        /**
         * The launcher's static "New macro" shortcut (`res/xml/shortcuts.xml`).
         *
         * Public and namespaced because it crosses a process boundary: the
         * shortcut definition names it as a string, and the launcher delivers it.
         * A private constant could not be referenced from the XML anyway, and an
         * un-namespaced one would collide with any other app's.
         */
        const val ACTION_NEW_MACRO = "io.github.m1n1m1.easymatic.action.NEW_MACRO"

        private const val ROUTE_GEOFENCES = "geofences"
        private const val ROUTE_NFC_TAGS = "nfcTags"
        private const val ROUTE_FOLDER_ACCESS = "folderAccess"
        private const val ROUTE_TRANSLATION_MODELS = "translationModels"
        private const val ROUTE_MAIL_ACCOUNTS = "mailAccounts"
        private const val ROUTE_SMART_HOME = "smartHome"
        private const val ROUTE_AI = "ai"
        private const val ROUTE_VARIABLES = "variables"
        private const val ROUTE_PERMISSIONS = "permissions"
        private const val ROUTE_PLUGINS = "plugins"
        private const val ROUTE_APP_ACCESS = "appAccess"
        private const val ROUTE_BACKUP = "backup"
        private const val ROUTE_LANGUAGE = "language"
        private const val ROUTE_INFO = "info"
        private const val ARG_WORKFLOW_ID = "workflowId"
    }
}
