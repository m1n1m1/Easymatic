package com.example.ottomatic.feature.widget

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.ColorRes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.color.ColorProvider
import androidx.glance.color.ColorProviders
import androidx.glance.material3.ColorProviders
import androidx.glance.unit.ColorProvider
import com.example.ottomatic.R
import com.example.ottomatic.domain.model.MacroAccent

/**
 * The palette the home-screen widgets draw in — the system's, not the app's.
 *
 * The app itself is forced dark and non-dynamic (`MainActivity` passes
 * `darkTheme = true, dynamicColor = false`) because the graph editor's canvas is
 * a fixed dark surface and a light theme would render a light bar over it. A
 * widget is not on that canvas; it is on somebody's wallpaper, next to Clock and
 * Calendar, and the rule the app already follows for surfaces outside itself is
 * the one in `themes.xml`: `Theme.Ottomatic.Dialog` takes `Theme.Material3.DayNight`
 * with dynamic colour precisely because a macro's dialog appears over other apps.
 * A widget is the same argument, so it gets the same answer.
 *
 * Below API 31 there is no wallpaper palette to take, so [FALLBACK] supplies one
 * built around `dialog_accent` — the colour the app's own action nodes wear, which
 * is a better answer than Material's baseline purple for a phone that has never
 * heard of Material You.
 */
@Composable
fun OttomaticWidgetTheme(content: @Composable () -> Unit) {
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) GlanceTheme.colors else FALLBACK
    GlanceTheme(colors = colors, content = content)
}

/**
 * A macro's accent as a **fill and the content drawn on it**.
 *
 * The pair, not a single colour, is what makes the widgets look like Material You
 * rather than like a dark card with a coloured smudge on it: every pressable thing
 * in the M3 vocabulary is a filled shape with readable content on it, and one
 * colour cannot be both.
 *
 * These follow the `primary` / `onPrimary` tones rather than the calmer
 * `primaryContainer` pair. That was the first attempt and it failed on a real home
 * screen — in dark mode a container tone sits about as bright as `widgetBackground`
 * itself, so a default-accent chip vanished into the card it was on.
 *
 * Both halves of the pair are **day/night** providers rather than single `Color`s,
 * because resolving to one colour here would pick a theme inside a composable that
 * has no idea which theme the home screen is in. The two values still come from
 * `values/` and `values-night/` — [dayNight] reads each resource twice, once under
 * each `uiMode` — so the colours stay stated in one place and the *choice* between
 * them is made where the widget is actually drawn.
 *
 * Note which of Glance's two `ColorProvider` factories that is. The resource-taking
 * one is `@RestrictTo` and says why in its own KDoc: it resolves in whichever process
 * gets there first, and for a widget that is the launcher's, under the launcher's
 * configuration rather than this app's. `ColorProvider(day, night)` carries both
 * answers across the process boundary and picks on arrival, which is the behaviour
 * this comment used to claim the resource form had.
 *
 * [MacroAccent.SYSTEM] has no resource and resolves to `primary` / `onPrimary` —
 * the same tones taken from the wallpaper, which is the whole promise of that
 * choice and not something a fixed resource can hold.
 */
data class AccentColors(val container: ColorProvider, val onContainer: ColorProvider)

@Composable
fun MacroAccent.widgetColors(): AccentColors = when (this) {
    MacroAccent.SYSTEM -> AccentColors(
        GlanceTheme.colors.primary,
        GlanceTheme.colors.onPrimary,
    )

    MacroAccent.RED -> pair(R.color.macro_container_red, R.color.macro_on_container_red)
    MacroAccent.ORANGE -> pair(R.color.macro_container_orange, R.color.macro_on_container_orange)
    MacroAccent.AMBER -> pair(R.color.macro_container_amber, R.color.macro_on_container_amber)
    MacroAccent.GREEN -> pair(R.color.macro_container_green, R.color.macro_on_container_green)
    MacroAccent.TEAL -> pair(R.color.macro_container_teal, R.color.macro_on_container_teal)
    MacroAccent.BLUE -> pair(R.color.macro_container_blue, R.color.macro_on_container_blue)
    MacroAccent.VIOLET -> pair(R.color.macro_container_violet, R.color.macro_on_container_violet)
    MacroAccent.PINK -> pair(R.color.macro_container_pink, R.color.macro_on_container_pink)
}

@Composable
private fun pair(@ColorRes container: Int, @ColorRes onContainer: Int): AccentColors {
    val context = LocalContext.current
    return AccentColors(context.dayNight(container), context.dayNight(onContainer))
}

/** One colour resource as the pair of values `values/` and `values-night/` give it. */
private fun Context.dayNight(@ColorRes id: Int): ColorProvider = ColorProvider(
    day = Color(ContextCompat.getColor(inNightMode(false), id)),
    night = Color(ContextCompat.getColor(inNightMode(true), id)),
)

private fun Context.inNightMode(night: Boolean): Context = createConfigurationContext(
    Configuration(resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    },
)

/**
 * The pre-Android-12 palette.
 *
 * Only `primary` and the error pair are stated; everything else is Material 3's
 * baseline neutral, which is a perfectly good grey and not worth restating. The
 * error red is `EditorColors.errorAccent`, so a failed run looks the same colour
 * on a home screen as it does in the editor's Problems panel.
 */
private val FALLBACK: ColorProviders = ColorProviders(
    light = lightColorScheme(
        primary = Color(0xFF2A5FC7),
        error = Color(0xFFC62F2A),
    ),
    dark = darkColorScheme(
        primary = Color(0xFF5B8DEF),
        error = Color(0xFFE5534B),
    ),
)
