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
import com.example.ottomatic.core.permissions.PermissionStatus
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.data.permissions.AndroidPermissionChecker
import com.example.ottomatic.feature.grapheditor.GraphEditorScreen
import com.example.ottomatic.feature.grapheditor.GraphEditorViewModel
import com.example.ottomatic.ui.theme.OttomaticTheme

class MainActivity : ComponentActivity() {

    private val viewModel: GraphEditorViewModel by viewModels {
        GraphEditorViewModel.factory(
            repository = ServiceLocator.workflowRepository,
            triggerHost = ServiceLocator.triggerHost,
            executionContext = ServiceLocator.executionContext,
        )
    }

    private val permissionChecker by lazy { AndroidPermissionChecker(this, this) }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        ServiceLocator.init(applicationContext)
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        requestForegroundLocationPermissionIfNeeded()
        requestDndPermissionIfNeeded()
        // The graph editor uses a fixed dark palette, so force light system bar icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            OttomaticTheme(darkTheme = true, dynamicColor = false) {
                GraphEditorScreen(viewModel)
            }
        }
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
}
