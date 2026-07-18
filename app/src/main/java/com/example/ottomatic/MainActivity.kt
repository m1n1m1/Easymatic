package com.example.ottomatic

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
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

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Result is ignored; the user can grant it later via Settings.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        ServiceLocator.init(applicationContext)
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
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
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
