package com.example.ottomatic

import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ottomatic.core.permissions.PermissionStatus
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.data.BootFailureStore
import com.example.ottomatic.data.permissions.AndroidPermissionChecker
import com.example.ottomatic.engine.service.MacroEngineService
import com.example.ottomatic.feature.grapheditor.GraphEditorScreen
import com.example.ottomatic.feature.grapheditor.GraphEditorViewModel
import com.example.ottomatic.feature.workflowlist.WorkflowListScreen
import com.example.ottomatic.feature.workflowlist.WorkflowListViewModel
import com.example.ottomatic.ui.theme.OttomaticTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val listViewModel: WorkflowListViewModel by viewModels {
        WorkflowListViewModel.factory(
            repository = ServiceLocator.workflowRepository,
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
                val navController = rememberNavController()
                NavHost(
                    navController = navController,
                    startDestination = ROUTE_WORKFLOW_LIST,
                ) {
                    composable(ROUTE_WORKFLOW_LIST) {
                        WorkflowListScreen(
                            viewModel = listViewModel,
                            onOpenWorkflow = { id -> navController.navigate("$ROUTE_GRAPH_EDITOR/$id") },
                        )
                    }
                    composable(
                        route = "$ROUTE_GRAPH_EDITOR/{$ARG_WORKFLOW_ID}",
                        arguments = listOf(navArgument(ARG_WORKFLOW_ID) { type = NavType.StringType }),
                    ) { backStackEntry ->
                        val workflowId = backStackEntry.arguments?.getString(ARG_WORKFLOW_ID).orEmpty()
                        val editorViewModel: GraphEditorViewModel by viewModels {
                            GraphEditorViewModel.factory(
                                repository = ServiceLocator.workflowRepository,
                                triggerHost = ServiceLocator.triggerHost,
                                executionContext = ServiceLocator.executionContext,
                                appContext = applicationContext,
                                workflowId = workflowId,
                            )
                        }
                        GraphEditorScreen(
                            viewModel = editorViewModel,
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
        }
    }

    override fun onResume() {
        super.onResume()
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
            data = Uri.parse("package:$packageName")
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (permissionChecker.status(Permissions.ACCESS_NOTIFICATION_POLICY) is PermissionStatus.Granted) return
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        requestDndPolicyAccess.launch(intent)
    }

    private companion object {
        const val ROUTE_WORKFLOW_LIST = "workflowList"
        const val ROUTE_GRAPH_EDITOR = "graphEditor"
        const val ARG_WORKFLOW_ID = "workflowId"
    }
}
