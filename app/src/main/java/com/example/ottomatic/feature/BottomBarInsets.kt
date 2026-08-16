package com.example.ottomatic.feature

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How much of the system's bottom inset a navigation bar keeps.
 *
 * The inset is padding *inside* the bar's surface, so it lands entirely below the
 * labels and makes the bar look bottom-heavy — the space above the icons is the
 * item's own padding, the space below it is that plus the inset. Under gesture
 * navigation the inset guards a thin handle drawn *over* the app, and the bar's own
 * padding already keeps the labels clear of it, so half of it is enough and the bar
 * sits that much lower.
 *
 * Three-button navigation is left alone: there the inset guards real buttons that
 * would swallow taps meant for the bar, and its size is what says which one is in
 * use — a handle reserves a strip, a button bar reserves a bar.
 *
 * One function rather than one per bar. The app now has two — the home shell's
 * `HomeBottomBar` and the editor's `EditorBottomBar` — and the rule they need is the
 * same rule for the same reason, so a second copy could only ever drift.
 */
@Composable
internal fun trimmedBottomInsets(): WindowInsets {
    val insets = WindowInsets.systemBars
    val bottom = with(LocalDensity.current) { insets.getBottom(this).toDp() }
    val kept = if (bottom <= GESTURE_HANDLE_MAX) bottom / 2 else bottom
    return insets.only(WindowInsetsSides.Horizontal).add(WindowInsets(bottom = kept))
}

/** Above this, a bottom inset is reserving buttons rather than a gesture handle. */
private val GESTURE_HANDLE_MAX: Dp = 32.dp
