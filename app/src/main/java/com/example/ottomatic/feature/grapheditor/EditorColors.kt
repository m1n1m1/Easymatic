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
import com.example.ottomatic.domain.model.schema.ItemSchema

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
    val execEdge = Color(0xFFE0E2EA)
    val execEdgeSelected = Color(0xFFFF8A65)
    val dataEdgeSelected = Color(0xFFFF8A65)
    val execPort = Color(0xFFE0E2EA)
    val chrome = Color(0xFF1E1F26)
    val chromeBorder = Color(0xFF32343D)
    val triggerAccent = Color(0xFFE06C4F)
    val actionAccent = Color(0xFF5B8DEF)

    /**
     * Per-data-type port colors, grouped from [ItemSchema] into a small
     * Blueprint-style palette (one color per *family* of types, not per type):
     *
     *  - String  — pink
     *  - Number  — green (Int/Long/Double/Float)
     *  - Boolean — red
     *  - Struct  — blue ([ItemSchema.Object])
     *  - Collection — orange ([ItemSchema.ListSchema] / [ItemSchema.MapSchema])
     *  - Wildcard / Union / Unit / unknown — gray
     *
     * Use [portTypeColor] to map a port's [ItemSchema] to its color.
     */
    val stringPort = Color(0xFFE573B8)
    val numberPort = Color(0xFF56C2A8)
    val booleanPort = Color(0xFFEF5350)
    val structPort = Color(0xFF5B8DEF)
    val collectionPort = Color(0xFFE0A04C)
    val wildcardPort = Color(0xFFB4B8C2)
}

fun accentColor(kind: NodeKind): Color = when (kind) {
    NodeKind.TRIGGER -> EditorColors.triggerAccent
    NodeKind.ACTION -> EditorColors.actionAccent
}

/**
 * Maps a port's [ItemSchema] to its color in the Blueprint-style palette
 * (see [EditorColors.stringPort] and friends). EXECUTION ports and unknown
 * schemas resolve to the gray wildcard color; callers should use
 * [EditorColors.execPort] for EXECUTION ports directly.
 */
fun portTypeColor(schema: ItemSchema?): Color = when (schema) {
    is ItemSchema.Primitive -> when (schema.kClass) {
        String::class -> EditorColors.stringPort
        Int::class, Long::class, Double::class, Float::class -> EditorColors.numberPort
        Boolean::class -> EditorColors.booleanPort
        else -> EditorColors.stringPort
    }
    is ItemSchema.Object -> EditorColors.structPort
    is ItemSchema.ListSchema, is ItemSchema.MapSchema -> EditorColors.collectionPort
    is ItemSchema.Wildcard, is ItemSchema.Union, is ItemSchema.Unit, null -> EditorColors.wildcardPort
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
