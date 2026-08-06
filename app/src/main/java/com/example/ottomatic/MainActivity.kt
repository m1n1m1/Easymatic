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
import com.example.ottomatic.data.BootFailureStore
import com.example.ottomatic.data.location.AndroidLocationLookup
import com.example.ottomatic.data.permissions.AndroidPermissionChecker
import com.example.ottomatic.domain.registry.GrantedPrerequisites
import com.example.ottomatic.engine.service.MacroEngineService
import com.example.ottomatic.feature.geofence.GeofencePlacesScreen
import com.example.ottomatic.feature.geofence.GeofencePlacesViewModel
import com.example.ottomatic.feature.grapheditor.GraphEditorScreen
import com.example.ottomatic.feature.grapheditor.GraphEditorViewModel
import com.example.ottomatic.feature.variables.GlobalVariablesScreen
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

    private val requestIgnoreBatteryOptimizations =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
            // Result is ignored; the user grants or denies in the system dialog.
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
     * The app's four destinations: workflow list, geofence library, global
     * variable library, graph editor.
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
                    onOpenVariables = { navController.navigate(ROUTE_VARIABLES) },
                )
            }
            composable(ROUTE_GEOFENCES) {
                GeofencePlacesScreen(
                    viewModel = geofencePlacesViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(ROUTE_VARIABLES) {
                GlobalVariablesScreen(
                    viewModel = globalVariablesViewModel,
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
                    globalVariables = globalVariablesViewModel,
                    onBack = { navController.popBackStack() },
                    showBatteryPrompt = showBatteryPrompt,
                    onDismissBatteryPrompt = { showBatteryPrompt = false },
                    onConfirmBatteryPrompt = {
                        showBatteryPrompt = false
                        requestBatteryOptimizationExemption()
                    },
                )
            }
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
        // actually failed AND at least one macro is armed (no point prompting if
        // nothing is enabled). Consume the flag so it shows at most once per
        // failure. Also reached when the user taps the BootFailureNotifier.
        lifecycleScope.launch {
            val needsPrompt = BootFailureStore.consume(this@MainActivity)
            if (needsPrompt && ServiceLocator.workflowRepository.list().any { it.enabled }) {
                showBatteryPrompt = true
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = "package:$packageName".toUri()
        }
        requestIgnoreBatteryOptimizations.launch(intent)
        // We are now in the foreground, so this session's re-arm will succeed
        // regardless of the exemption outcome. The exemption above helps the
        // *next* reboot succeed without intervention.
        MacroEngineService.start(this, MacroEngineService.ACTION_REARM_ALL)
    }

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
        private const val ROUTE_VARIABLES = "variables"
        private const val ARG_WORKFLOW_ID = "workflowId"
    }
}
