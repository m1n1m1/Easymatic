package com.example.ottomatic.feature.grapheditor

import com.example.ottomatic.R
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Dock
import androidx.compose.material.icons.filled.DoNotDisturb
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SensorOccupied
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tag
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

    /** Console warnings. Amber reads as "look at this" without reading as broken. */
    val warnAccent = Color(0xFFE0A04C)

    /**
     * Something is broken: a quarantined node or wire, and the problems badge.
     *
     * Its own colour rather than [triggerAccent], which is already the trigger
     * kind's accent, the console badge and the delete tint — and which sits one
     * step from [nodeSelectedBorder]. An error border in that orange on an
     * unselected trigger card reads as "selected", which is the one thing a border
     * on this canvas already means.
     */
    val errorAccent = Color(0xFFE5534B)
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
     *  - Collection — orange ([ItemSchema.MapSchema])
     *  - Wildcard / Union / Unit / unknown — gray
     *
     * A [ItemSchema.ListSchema] takes its *element's* color and is told apart by
     * its handle shape instead — see [portIsList].
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

/** The word shown under a node's name on its card. */
@StringRes
fun kindLabelRes(kind: NodeKind): Int = when (kind) {
    NodeKind.TRIGGER -> R.string.kind_trigger
    NodeKind.ACTION -> R.string.kind_action
    NodeKind.VALUE -> R.string.kind_value
    NodeKind.TRANSFORM -> R.string.kind_transform
}

/**
 * The same word in the plural, for the palette's kind headings.
 *
 * A second key rather than appending "s" to the first: English gets away with that
 * and German does not — "Auslöser" is both forms, "Aktionen" is neither.
 */
@StringRes
fun kindLabelPluralRes(kind: NodeKind): Int = when (kind) {
    NodeKind.TRIGGER -> R.string.kind_triggers
    NodeKind.ACTION -> R.string.kind_actions
    NodeKind.VALUE -> R.string.kind_values
    NodeKind.TRANSFORM -> R.string.kind_transforms
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
    // A list wears its element's color and says "several" with its *shape* instead
    // ([portIsList]). One orange for every collection could not tell a list of dates
    // from a list of text, which is exactly what someone wiring a loop needs to see.
    is ItemSchema.ListSchema -> portTypeColor(schema.element)
    is ItemSchema.MapSchema -> EditorColors.collectionPort
    is ItemSchema.Wildcard, is ItemSchema.Union, is ItemSchema.Unit, null -> EditorColors.wildcardPort
}

/**
 * Whether a port carries several values rather than one, and so is drawn as a
 * stacked handle instead of a single ring.
 *
 * Unreal Blueprints' array-pin convention: color answers "of what?", shape answers
 * "how many?". Keeping them on separate axes is what lets a list of text stay
 * recognisably text.
 */
fun portIsList(schema: ItemSchema?): Boolean = schema is ItemSchema.ListSchema

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
    NodeIcon.MAIL -> Icons.Filled.Mail
    NodeIcon.HTTP -> Icons.Filled.Http
    NodeIcon.WIFI -> Icons.Filled.Wifi
    NodeIcon.NFC -> Icons.Filled.Nfc
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
    NodeIcon.CODE -> Icons.Filled.Code
    NodeIcon.VARIABLE -> Icons.Filled.Tag
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
    NodeIcon.LOOP -> Icons.Filled.Loop
    NodeIcon.LIST -> Icons.AutoMirrored.Filled.FormatListBulleted
    NodeIcon.DIALOG -> Icons.Filled.ChatBubbleOutline
    NodeIcon.QUESTION -> Icons.AutoMirrored.Filled.HelpOutline
    NodeIcon.INPUT -> Icons.Filled.EditNote
    NodeIcon.CHOICE -> Icons.Filled.Checklist
    // Distinct from LIGHT, which is the ambient-light sensor: one is a bulb you
    // switch, the other is how bright the room is.
    NodeIcon.LIGHTBULB -> Icons.Filled.Lightbulb
    NodeIcon.HOME -> Icons.Filled.Home
    NodeIcon.SCENE -> Icons.Filled.AutoAwesome
    // Distinct from DIALOG's speech bubble, which is Ottomatic talking to the user.
    NodeIcon.CHAT -> Icons.AutoMirrored.Filled.Message
    NodeIcon.AI -> Icons.Filled.Psychology
    // Two icons rather than one so the four nodes acting on a named file read
    // differently at a glance from the one that answers with a folder's contents.
    NodeIcon.FILE -> Icons.AutoMirrored.Filled.InsertDriveFile
    NodeIcon.FOLDER -> Icons.Filled.FolderOpen
    // A month grid rather than SCHEDULE's clock: this is a diary somebody else keeps,
    // not a rule the app follows.
    NodeIcon.CALENDAR -> Icons.Filled.CalendarMonth
    // A picture frame rather than FILE's document: an image node addresses a row in the
    // media collection, which is not the same thing a file node can open.
    NodeIcon.IMAGE -> Icons.Filled.Image
    // Crop marks rather than the frame, for the one image node that rewrites pixels.
    NodeIcon.IMAGE_EDIT -> Icons.Filled.Crop
    // A lens rather than a frame: this marks "take one", where IMAGE marks "one that exists".
    // A phone with a frame around it rather than IMAGE's picture: these nodes are about
    // this screen, not about a photograph of somewhere else.
    NodeIcon.SCREENSHOT -> Icons.Filled.Screenshot
    NodeIcon.CAMERA -> Icons.Filled.PhotoCamera
    // The code itself rather than the camera that reads it — what the button promises is a
    // value, and which sensor another app uses to get it is not the user's question.
    NodeIcon.QR_CODE -> Icons.Filled.QrCodeScanner
}
