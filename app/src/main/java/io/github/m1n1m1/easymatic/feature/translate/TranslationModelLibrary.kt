package io.github.m1n1m1.easymatic.feature.translate

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The downloaded-language library, plus the way to go and change it.
 *
 * `MacroLibrary`'s and `EditorVariableLibrary`'s shape — a small wrapper published to the config
 * form, because a `@Picker` field is generic code several composables deep and there is no way to
 * reach it without threading a language-shaped parameter through everything in between.
 *
 * **[openSettings] is the part that is unusual, and it is unusual because this library is the one
 * the editor cannot edit.** Every other picker in the app can create what it is short of without
 * leaving: the AI chooser adds a connection from inside its own overlay, the variable chooser
 * declares a variable. Downloading a language is deliberately not that — it belongs on the
 * Translation models screen, where somebody has gone to spend the data on purpose — so the chooser
 * offers a way *there* instead of a way to do it in place.
 *
 * Null when no provider is in scope (previews, tests). The chooser then shows the languages it
 * knows about and no link, which is the honest degradation: a link that cannot navigate is worse
 * than none.
 */
class TranslationModelLibrary(
    val models: TranslationModelsViewModel,
    /** Leaves the editor for the Translation models screen. */
    val openSettings: () -> Unit,
)

val LocalTranslationModels = staticCompositionLocalOf<TranslationModelLibrary?> { null }
