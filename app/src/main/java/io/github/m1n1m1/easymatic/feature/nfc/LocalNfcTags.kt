package io.github.m1n1m1.easymatic.feature.nfc

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The tag library, published to the config form.
 *
 * Same reasoning as `LocalGeofencePlaces`: a `@Picker` field is generic code
 * several composables deep, and there is no way to reach it without threading a
 * tag-shaped parameter through everything in between.
 *
 * Null when no provider is in scope (previews, tests): callers fall back to showing
 * the raw id rather than crashing. Which is a softer fallback here than it is for a
 * place — a uid *is* what the node stores, so an unresolved one is merely unfriendly
 * rather than meaningless.
 */
val LocalNfcTags = staticCompositionLocalOf<NfcTagsViewModel?> { null }
