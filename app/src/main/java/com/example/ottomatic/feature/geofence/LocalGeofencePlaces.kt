package com.example.ottomatic.feature.geofence

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The geofence library, made available to the parts of the editor that are too
 * deep to be handed a ViewModel.
 *
 * The config form renders whatever the node schema says, several composables
 * below the screen that owns the ViewModels — so a `@Picker` field and a node
 * card both need the place list without any of their callers knowing a geofence
 * exists. Threading a parameter for that through every generic config
 * composable would make the whole form know about one node type, which is
 * exactly what the schema-driven design avoids.
 *
 * Null when no provider is in scope (previews, tests): callers fall back to
 * showing the raw id rather than crashing.
 */
val LocalGeofencePlaces = staticCompositionLocalOf<GeofencePlacesViewModel?> { null }
