package io.github.m1n1m1.easymatic.feature.macro

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.m1n1m1.easymatic.R
import io.github.m1n1m1.easymatic.domain.model.MacroAccent
import io.github.m1n1m1.easymatic.domain.model.MacroIcon
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors

/**
 * How a macro's chosen [MacroIcon] and [MacroAccent] turn into something drawable.
 *
 * Lives in `feature/macro/` rather than under either consumer because there are
 * three of them and they are not siblings: the workflow list row, the home-screen
 * widgets and the launcher shortcuts. Putting it under `feature/widget/` would
 * have made the list screen import the widget package to draw a row.
 *
 * The mapping is to a **drawable resource**, once, for all three. `NodeIcon` maps
 * to a Compose `ImageVector` (`EditorColors.nodeIcon`) and that is fine for a node
 * — nothing outside the canvas draws one. A macro's icon has to survive being
 * handed to Glance and to `ShortcutInfoCompat`, neither of which can take an
 * `ImageVector`, while Compose renders a drawable perfectly well through
 * [painterResource]. One mapping that all three can use beats two that drift.
 */

/**
 * The 24dp vector for [MacroIcon].
 *
 * Exhaustive with no `else`, so adding an enum constant without drawing it is a
 * compile error rather than a blank square on someone's home screen — the same
 * guarantee `nodeIcon` gives on the canvas.
 */
@Suppress("CyclomaticComplexMethod") // A flat, exhaustive icon table, not branching logic.
@DrawableRes
fun MacroIcon.drawableRes(): Int = when (this) {
    MacroIcon.BOLT -> R.drawable.ic_macro_bolt
    MacroIcon.FLASHLIGHT -> R.drawable.ic_macro_flashlight
    MacroIcon.VOLUME_OFF -> R.drawable.ic_macro_volume_off
    MacroIcon.VOLUME_UP -> R.drawable.ic_macro_volume_up
    MacroIcon.WIFI -> R.drawable.ic_macro_wifi
    MacroIcon.BLUETOOTH -> R.drawable.ic_macro_bluetooth
    MacroIcon.AIRPLANE -> R.drawable.ic_macro_airplane
    MacroIcon.LOCATION -> R.drawable.ic_macro_location
    MacroIcon.CAR -> R.drawable.ic_macro_car
    MacroIcon.HOME -> R.drawable.ic_macro_home
    MacroIcon.WORK -> R.drawable.ic_macro_work
    MacroIcon.SUN -> R.drawable.ic_macro_sun
    MacroIcon.MOON -> R.drawable.ic_macro_moon
    MacroIcon.SLEEP -> R.drawable.ic_macro_sleep
    MacroIcon.ALARM -> R.drawable.ic_macro_alarm
    MacroIcon.TIMER -> R.drawable.ic_macro_timer
    MacroIcon.MUSIC -> R.drawable.ic_macro_music
    MacroIcon.CAMERA -> R.drawable.ic_macro_camera
    MacroIcon.PHONE -> R.drawable.ic_macro_phone
    MacroIcon.MESSAGE -> R.drawable.ic_macro_message
    MacroIcon.LOCK -> R.drawable.ic_macro_lock
    MacroIcon.BATTERY -> R.drawable.ic_macro_battery
    MacroIcon.COFFEE -> R.drawable.ic_macro_coffee
    MacroIcon.STAR -> R.drawable.ic_macro_star
}

/**
 * The colour resource for [MacroAccent], or `null` for [MacroAccent.SYSTEM].
 *
 * Null rather than a stand-in because SYSTEM genuinely has no fixed value: on a
 * widget it is the wallpaper accent, resolved at render time from a
 * `ColorProvider`, and inside the app it is whatever [inAppColor] decides. A
 * resource id here would be a third answer that agrees with neither.
 *
 * `MacroAccent.argb` is a fourth, and the one that is *not* a surface this app draws:
 * a notification is painted by the system, so there is no configuration to resolve a
 * day/night qualifier against and it carries the night values as a plain number. Keep
 * the three tables in step by value; they cannot be one table, because `data/` may not
 * import `feature/` and `core/` may not import `domain/`.
 */
@ColorRes
fun MacroAccent.colorRes(): Int? = when (this) {
    MacroAccent.SYSTEM -> null
    MacroAccent.RED -> R.color.macro_accent_red
    MacroAccent.ORANGE -> R.color.macro_accent_orange
    MacroAccent.AMBER -> R.color.macro_accent_amber
    MacroAccent.GREEN -> R.color.macro_accent_green
    MacroAccent.TEAL -> R.color.macro_accent_teal
    MacroAccent.BLUE -> R.color.macro_accent_blue
    MacroAccent.VIOLET -> R.color.macro_accent_violet
    MacroAccent.PINK -> R.color.macro_accent_pink
}

/**
 * The colour [MacroAccent] takes **inside the app**, which is a fixed dark surface
 * (`MainActivity` passes `darkTheme = true, dynamicColor = false`).
 *
 * These are `EditorColors` values, not the `values-night` resources, and reading
 * from the object rather than the resource folder is deliberate: the app never
 * resolves a night qualifier, so `colorResource(macro_accent_blue)` would hand
 * back the *light* value on a light-themed phone and paint a navy glyph on the
 * near-black canvas. Taking the constant directly is what makes a macro's blue the
 * same blue its action nodes wear two screens away.
 *
 * [MacroAccent.SYSTEM] resolves to `actionAccent`, because the app has no
 * wallpaper accent to defer to — that promise is only meaningful on a widget.
 */
fun MacroAccent.inAppColor(): Color = when (this) {
    MacroAccent.SYSTEM -> EditorColors.actionAccent
    MacroAccent.RED -> EditorColors.errorAccent
    MacroAccent.ORANGE -> EditorColors.triggerAccent
    MacroAccent.AMBER -> EditorColors.warnAccent
    // The one accent with no counterpart in EditorColors: the editor's green-ish
    // slot is numberPort, which is a teal, and TEAL already has it.
    MacroAccent.GREEN -> GREEN_ACCENT
    MacroAccent.TEAL -> EditorColors.numberPort
    MacroAccent.BLUE -> EditorColors.actionAccent
    MacroAccent.VIOLET -> EditorColors.valueAccent
    MacroAccent.PINK -> EditorColors.stringPort
}

/**
 * A macro's identity as one in-app element: the glyph on a rounded, faintly
 * accent-tinted square.
 *
 * The tint is the accent at [CHIP_FILL_ALPHA] rather than a solid fill for the
 * same reason the widget tiles do it — a saturated block the size of a list row
 * competes with the macro's name, which is the thing being read. It is one
 * composable rather than one per caller so the list row, the picker's grid and the
 * pin dialog cannot disagree about what a macro looks like.
 */
@Composable
fun MacroIconChip(
    icon: MacroIcon,
    accent: MacroAccent,
    modifier: Modifier = Modifier,
    size: Dp = CHIP_SIZE,
    glyphSize: Dp = GLYPH_SIZE,
) {
    val color = accent.inAppColor()
    Box(
        modifier = modifier
            .size(size)
            .background(color.copy(alpha = CHIP_FILL_ALPHA), RoundedCornerShape(size / CHIP_RADIUS_RATIO)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon.drawableRes()),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(glyphSize),
        )
    }
}

private val GREEN_ACCENT = Color(0xFF6FCF71)

private const val CHIP_FILL_ALPHA = 0.16f
private const val CHIP_RADIUS_RATIO = 3f
private val CHIP_SIZE = 38.dp
private val GLYPH_SIZE = 21.dp
