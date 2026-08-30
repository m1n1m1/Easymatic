package io.github.m1n1m1.easymatic.feature.smarthome

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The smart-home hub library, for the config form's light and scene pickers.
 *
 * A [staticCompositionLocalOf] for the reason the geofence, variable, tag and mail
 * libraries have one: the picker sits several composables inside a generic config
 * form that knows nothing about lights, so threading a ViewModel down to it would
 * mean a parameter on every layer in between.
 *
 * The `identifier-pickers` rule is that the app list and the macro list get no
 * local, because those locals exist so a picker can *edit* its library and see its
 * own in-flight edits. This one does exactly that: for almost everybody the first
 * bridge they ever pair will be paired from inside this overlay, having dropped a
 * Control Light node and found the field empty.
 *
 * Null when no provider is in scope — a preview, a test — and the pickers then fall
 * back to the name cached in the reference itself, which is the one thing they can
 * do that no other picker can.
 */
val LocalSmartHome = staticCompositionLocalOf<SmartHomeViewModel?> { null }
