package com.example.ottomatic.feature.ai

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The AI connection library, for the config form's connection picker.
 *
 * A [staticCompositionLocalOf] for the reason the geofence, variable, tag, mail and
 * smart-home libraries have one: the picker sits several composables inside a
 * generic config form that knows nothing about AI, so threading a ViewModel down to
 * it would mean a parameter on every layer in between.
 *
 * The `identifier-pickers` rule is that the app list and the macro list get no
 * local, because those locals exist so a picker can *edit* its library and see its
 * own in-flight edits. This one does exactly that, and more literally than most:
 * for almost everybody the first connection they ever add will be added from inside
 * this overlay, having dropped an Ask AI node and found the field empty.
 *
 * Null when no provider is in scope — a preview, a test — and the field then
 * renders disabled rather than guessing, since unlike a light target there is no
 * name cached in the reference to fall back on.
 */
val LocalAiConnections = staticCompositionLocalOf<AiConnectionsViewModel?> { null }
