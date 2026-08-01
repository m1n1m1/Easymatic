package com.example.ottomatic.feature.grapheditor

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Dock
import androidx.compose.material.icons.filled.DoNotDisturb
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SensorOccupied
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.schema.DateTime
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
    val valueAccent = Color(0xFFC58AF9)

    // Transforms sit next to values on the pull side, so they share the purple
    // family — a cooler, dimmer shade, since a transform is plumbing rather than a
    // reading of the device.
    val transformAccent = Color(0xFF8E9CF7)

    // The box selection borrows the selected-node hue so that what it is about to
    // do is legible before it happens. The fill is barely there — it sits over the
    // cards it is capturing and must not obscure them.
    val marqueeStroke = Color(0xFFFF8A65)
    val marqueeFill = Color(0x1FFF8A65)

    /**
     * Per-data-type port colors, grouped from [ItemSchema] into a small
     * Blueprint-style palette (one color per *family* of types, not per type):
     *
     *  - String  — pink
     *  - Number  — green (Int/Long/Double/Float)
     *  - Boolean — red
     *  - Date & time — violet ([com.example.ottomatic.domain.model.schema.DateTime])
     *  - Struct  — blue ([ItemSchema.Object])
     *  - Collection — orange ([ItemSchema.ListSchema] / [ItemSchema.MapSchema])
     *  - Wildcard / Union / Unit / unknown — gray
     *
     * A moment gets its own color rather than joining the numbers: telling a
     * timestamp apart from a battery percentage at a glance is most of the reason
     * it is a type of its own.
     *
     * Use [portTypeColor] to map a port's [ItemSchema] to its color.
     */
    val stringPort = Color(0xFFE573B8)
    val numberPort = Color(0xFF56C2A8)
    val booleanPort = Color(0xFFEF5350)
    val dateTimePort = Color(0xFFA97BF0)
    val structPort = Color(0xFF5B8DEF)
    val collectionPort = Color(0xFFE0A04C)
    val wildcardPort = Color(0xFFB4B8C2)
}

fun accentColor(kind: NodeKind): Color = when (kind) {
    NodeKind.TRIGGER -> EditorColors.triggerAccent
    NodeKind.ACTION -> EditorColors.actionAccent
    NodeKind.VALUE -> EditorColors.valueAccent
    NodeKind.TRANSFORM -> EditorColors.transformAccent
}

/** The word shown under a node's name on its card, and in the palette headers. */
fun kindLabel(kind: NodeKind): String = when (kind) {
    NodeKind.TRIGGER -> "Trigger"
    NodeKind.ACTION -> "Action"
    NodeKind.VALUE -> "Value"
    NodeKind.TRANSFORM -> "Transform"
}

/** The glyph that stands for a whole node kind, used on the palette's kind cards. */
fun kindIcon(kind: NodeKind): ImageVector = when (kind) {
    NodeKind.TRIGGER -> Icons.Filled.Bolt
    NodeKind.ACTION -> Icons.Filled.PlayArrow
    NodeKind.VALUE -> Icons.Filled.Numbers
    NodeKind.TRANSFORM -> Icons.Filled.SwapHoriz
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
        DateTime::class -> EditorColors.dateTimePort
        else -> EditorColors.stringPort
    }
    is ItemSchema.Object -> EditorColors.structPort
    is ItemSchema.ListSchema, is ItemSchema.MapSchema -> EditorColors.collectionPort
    is ItemSchema.Wildcard, is ItemSchema.Union, is ItemSchema.Unit, null -> EditorColors.wildcardPort
}

/**
 * Maps a node type's [NodeIcon] to its vector asset. Exhaustive by construction:
 * adding an icon to the enum is a compile error until it is drawn here.
 */
@Suppress("CyclomaticComplexMethod") // A flat, exhaustive icon table, not branching logic.
fun nodeIcon(icon: NodeIcon): ImageVector = when (icon) {
    NodeIcon.BOLT -> Icons.Filled.Bolt
    NodeIcon.SPLIT -> Icons.AutoMirrored.Filled.CallSplit
    NodeIcon.TIMER -> Icons.Filled.Timer
    NodeIcon.SCHEDULE -> Icons.Filled.Schedule
    NodeIcon.NOTIFICATION -> Icons.Filled.NotificationsActive
    NodeIcon.SMS -> Icons.Filled.Sms
    NodeIcon.SEND -> Icons.AutoMirrored.Filled.Send
    NodeIcon.HTTP -> Icons.Filled.Http
    NodeIcon.WIFI -> Icons.Filled.Wifi
    NodeIcon.BLUETOOTH -> Icons.Filled.Bluetooth
    NodeIcon.VOLUME -> Icons.AutoMirrored.Filled.VolumeUp
    NodeIcon.MUSIC -> Icons.Filled.MusicNote
    NodeIcon.MUSIC_OFF -> Icons.Filled.MusicOff
    NodeIcon.DND -> Icons.Filled.DoNotDisturb
    NodeIcon.LOCATION -> Icons.Filled.LocationOn
    NodeIcon.BOOT -> Icons.Filled.PowerSettingsNew
    NodeIcon.BATTERY_LEVEL -> Icons.Filled.BatteryStd
    NodeIcon.BATTERY_CHARGING -> Icons.Filled.BatteryChargingFull
    NodeIcon.CONVERT -> Icons.Filled.SwapHoriz
    NodeIcon.TEXT -> Icons.Filled.TextFields
    NodeIcon.JSON -> Icons.Filled.DataObject
    NodeIcon.ORIENTATION -> Icons.Filled.ScreenRotation
    NodeIcon.SHAKE -> Icons.Filled.Vibration
    NodeIcon.TAP -> Icons.Filled.TouchApp
    NodeIcon.MOTION -> Icons.Filled.OpenWith
    NodeIcon.PROXIMITY -> Icons.Filled.SensorOccupied
    NodeIcon.LIGHT -> Icons.Filled.LightMode
    NodeIcon.POWER_SAVE -> Icons.Filled.BatterySaver
    NodeIcon.HEADSET -> Icons.Filled.Headphones
    NodeIcon.DOCK -> Icons.Filled.Dock
    NodeIcon.DARK_MODE -> Icons.Filled.DarkMode
}
