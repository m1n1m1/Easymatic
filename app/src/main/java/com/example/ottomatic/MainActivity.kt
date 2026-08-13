package com.example.ottomatic

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.graphics.Color
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ottomatic.core.permissions.PermissionStatus
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.data.BootFailureStore
import com.example.ottomatic.data.location.AndroidLocationLookup
import com.example.ottomatic.data.permissions.AndroidPermissionChecker
import com.example.ottomatic.domain.registry.GrantedPrerequisites
import com.example.ottomatic.engine.service.MacroEngineService
import com.example.ottomatic.feature.ai.AiConnectionsScreen
import com.example.ottomatic.feature.ai.AiConnectionsViewModel
import com.example.ottomatic.feature.geofence.GeofencePlacesScreen
import com.example.ottomatic.feature.geofence.GeofencePlacesViewModel
import com.example.ottomatic.feature.mail.MailAccountsScreen
import com.example.ottomatic.feature.smarthome.SmartHomeScreen
import com.example.ottomatic.feature.smarthome.SmartHomeViewModel
import com.example.ottomatic.feature.mail.MailAccountsViewModel
import com.example.ottomatic.feature.nfc.NfcTagsScreen
import com.example.ottomatic.feature.nfc.NfcTagsViewModel
import com.example.ottomatic.feature.grapheditor.GraphEditorScreen
import com.example.ottomatic.feature.grapheditor.GraphEditorViewModel
import com.example.ottomatic.feature.variables.GlobalVariablesScreen
import com.example.ottomatic.feature.permissions.BatteryOptimisationDialog
import com.example.ottomatic.feature.permissions.PermissionsScreen
import com.example.ottomatic.feature.api.ApiAccessScreen
import com.example.ottomatic.feature.plugins.PluginsScreen
import com.example.ottomatic.engine.api.listApiTriggers
import com.example.ottomatic.feature.variables.GlobalVariablesViewModel
import com.example.ottomatic.feature.workflowlist.WorkflowListScreen
import com.example.ottomatic.feature.workflowlist.WorkflowListViewModel
import com.example.ottomatic.ui.theme.OttomaticTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val listViewModel: WorkflowListViewModel by viewModels {
        WorkflowListViewModel.factory(
            repository = ServiceLocator.workflowRepository,
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
            ai = ServiceLocator.executionContext.ai,
            catalog = ServiceLocator.aiModelCatalog,
            appContext = applicationContext,
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

    private val permissionChecker by lazy { AndroidPermissionChecker(this, this) }

    // Set in onResume when BootFailureStore has a pending flag and a macro is
    // enabled. Rendered as a battery-optimisation dialog over whichever screen
    // is showing.
    private var showBatteryPrompt by mutableStateOf(false)

    private val requestForegroundLocation =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val fineGranted = result[Permissions.ACCESS_FINE_LOCATION.manifest] == true ||
                permissionChecker.status(Permissions.ACCESS_FINE_LOCATION) is PermissionStatus.Granted
            if (fineGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requestBackgroundPermissionIfNeeded()
            }
        }

    private val requestBackgroundLocation =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Result is ignored; the user can grant it later via Settings.
        }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Result is ignored; the user can grant it later via Settings.
        }

    private val requestDndPolicyAccess =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
            // Result is ignored; the user grants access in the system Settings
            // page and returns. They can always re-open it via the DND node.
        }

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
        // ServiceLocator is initialised in OttomaticApplication.onCreate(), which
        // runs before any Activity or manifest receiver, so it is ready here.
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        requestForegroundLocationPermissionIfNeeded()
        requestDndPermissionIfNeeded()
        // The app uses a fixed dark palette, so force light system bar icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            OttomaticTheme(darkTheme = true, dynamicColor = false) {
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
     * The app's destinations: workflow list, the four libraries (geofence, NFC
     * tag, mail account, global variable), permissions, graph editor.
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
            startDestination = ROUTE_WORKFLOW_LIST,
            // Mid-slide neither screen covers the full width; the gap shows
            // the window background, which Theme.Ottomatic pins to the
            // canvas colour so nothing flashes at the edge.
            enterTransition = { slideIntoContainer(SlideDirection.Left) },
            exitTransition = { slideOutOfContainer(SlideDirection.Left) },
            popEnterTransition = { slideIntoContainer(SlideDirection.Right) },
            popExitTransition = { slideOutOfContainer(SlideDirection.Right) },
        ) {
            composable(ROUTE_WORKFLOW_LIST) {
                WorkflowListScreen(
                    viewModel = listViewModel,
                    onOpenWorkflow = { id -> navController.navigate("$ROUTE_GRAPH_EDITOR/$id") },
                    onOpenGeofences = { navController.navigate(ROUTE_GEOFENCES) },
                    onOpenNfcTags = { navController.navigate(ROUTE_NFC_TAGS) },
                    onOpenMailAccounts = { navController.navigate(ROUTE_MAIL_ACCOUNTS) },
                    onOpenSmartHome = { navController.navigate(ROUTE_SMART_HOME) },
                    onOpenAi = { navController.navigate(ROUTE_AI) },
                    onOpenVariables = { navController.navigate(ROUTE_VARIABLES) },
                    onOpenPermissions = { navController.navigate(ROUTE_PERMISSIONS) },
                    onOpenPlugins = { navController.navigate(ROUTE_PLUGINS) },
                    onOpenAppAccess = { navController.navigate(ROUTE_APP_ACCESS) },
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
                        triggerHost = ServiceLocator.triggerHost,
                        executionContext = ServiceLocator.executionContext,
                        runLog = ServiceLocator.runLog,
                        appContext = applicationContext,
                        appScope = ServiceLocator.appScope,
                        workflowId = workflowId,
                        globalVariables = ServiceLocator.globalVariableRepository.variables,
                    ),
                )
                GraphEditorScreen(
                    viewModel = editorViewModel,
                    geofencePlaces = geofencePlacesViewModel,
                    nfcTags = nfcTagsViewModel,
                    mailAccounts = mailAccountsViewModel,
                    smartHome = smartHomeViewModel,
                    aiConnections = aiConnectionsViewModel,
                    globalVariables = globalVariablesViewModel,
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
    }

    override fun onResume() {
        super.onResume()
        // Every one of these is granted by leaving the app — a runtime dialog or a
        // Settings page — so coming back is exactly when the answer can have
        // changed, and is the only signal we get that it did. Cheap enough to
        // repeat: a handful of synchronous checks over the distinct grants the node
        // types declare.
        GrantedPrerequisites.hydrateFrom(ServiceLocator.permissionChecker)
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

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (permissionChecker.status(Permissions.POST_NOTIFICATIONS) is PermissionStatus.Granted) return
        requestNotificationPermission.launch(Permissions.POST_NOTIFICATIONS.manifest)
    }

    private fun requestForegroundLocationPermissionIfNeeded() {
        val fine = permissionChecker.status(Permissions.ACCESS_FINE_LOCATION)
        if (fine is PermissionStatus.Granted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requestBackgroundPermissionIfNeeded()
            }
            return
        }
        requestForegroundLocation.launch(
            arrayOf(
                Permissions.ACCESS_FINE_LOCATION.manifest,
                Permissions.ACCESS_COARSE_LOCATION.manifest,
            ),
        )
    }

    private fun requestBackgroundPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (permissionChecker.status(Permissions.ACCESS_BACKGROUND_LOCATION) is PermissionStatus.Granted) return
        requestBackgroundLocation.launch(Permissions.ACCESS_BACKGROUND_LOCATION.manifest)
    }

    private fun requestDndPermissionIfNeeded() {
        if (permissionChecker.status(Permissions.ACCESS_NOTIFICATION_POLICY) is PermissionStatus.Granted) return
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        requestDndPolicyAccess.launch(intent)
    }

    companion object {
        /**
         * The launcher's static "New macro" shortcut (`res/xml/shortcuts.xml`).
         *
         * Public and namespaced because it crosses a process boundary: the
         * shortcut definition names it as a string, and the launcher delivers it.
         * A private constant could not be referenced from the XML anyway, and an
         * un-namespaced one would collide with any other app's.
         */
        const val ACTION_NEW_MACRO = "com.example.ottomatic.action.NEW_MACRO"

        private const val ROUTE_WORKFLOW_LIST = "workflowList"
        private const val ROUTE_GRAPH_EDITOR = "graphEditor"
        private const val ROUTE_GEOFENCES = "geofences"
        private const val ROUTE_NFC_TAGS = "nfcTags"
        private const val ROUTE_MAIL_ACCOUNTS = "mailAccounts"
        private const val ROUTE_SMART_HOME = "smartHome"
        private const val ROUTE_AI = "ai"
        private const val ROUTE_VARIABLES = "variables"
        private const val ROUTE_PERMISSIONS = "permissions"
        private const val ROUTE_PLUGINS = "plugins"
        private const val ROUTE_APP_ACCESS = "appAccess"
        private const val ARG_WORKFLOW_ID = "workflowId"
    }
}
