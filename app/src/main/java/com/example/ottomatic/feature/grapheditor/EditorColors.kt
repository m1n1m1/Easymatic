package com.example.ottomatic.feature.grapheditor

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.ottomatic.domain.model.NodeKind

/**
 * Fixed dark palette for the graph editor, inspired by n8n's canvas.
 */
object EditorColors {
    val canvasBackground = Color(0xFF17181D)
    val gridDot = Color(0xFF2E3038)
    val nodeBackground = Color(0xFF24252D)
    val nodeBorder = Color(0xFF3A3C47)
    val nodeSelectedBorder = Color(0xFFFF8A65)
    val textPrimary = Color(0xFFECEDEF)
    val textSecondary = Color(0xFF9A9DA7)
    val edge = Color(0xFF6F7280)
    val edgeSelected = Color(0xFFFF8A65)
    val edgePending = Color(0xFF8AB4F8)
    val port = Color(0xFFB4B8C2)
    val portSnap = Color(0xFF8AB4F8)
    val triggerAccent = Color(0xFFE06C4F)
    val actionAccent = Color(0xFF5B8DEF)
    val chrome = Color(0xFF1E1F26)
    val chromeBorder = Color(0xFF32343D)
}

fun accentColor(kind: NodeKind): Color = when (kind) {
    NodeKind.TRIGGER -> EditorColors.triggerAccent
    NodeKind.ACTION -> EditorColors.actionAccent
}

fun nodeIcon(iconKey: String): ImageVector = when (iconKey) {
    "bolt" -> Icons.Filled.Bolt
    "schedule" -> Icons.Filled.Schedule
    "notification" -> Icons.Filled.NotificationsActive
    "sms" -> Icons.Filled.Sms
    "boot" -> Icons.Filled.PowerSettingsNew
    "http" -> Icons.Filled.Http
    "split" -> Icons.AutoMirrored.Filled.CallSplit
    "send" -> Icons.AutoMirrored.Filled.Send
    "timer" -> Icons.Filled.Timer
    "wifi" -> Icons.Filled.Wifi
    else -> Icons.Filled.Extension
}
