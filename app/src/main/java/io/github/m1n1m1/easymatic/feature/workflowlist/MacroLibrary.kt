package io.github.m1n1m1.easymatic.feature.workflowlist

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.m1n1m1.easymatic.domain.model.WorkflowSummary
import kotlinx.coroutines.flow.StateFlow

/**
 * The macros a `@Picker(MACRO)` field can point at, and which one is being edited.
 *
 * [editingId] is carried so the picker can *mark* the current workflow rather than
 * hide it: `action.disable_macro` pointing at its own macro is the "run once, then
 * switch me off" pattern, so excluding it would delete a capability to prevent a
 * mistake that is not one.
 */
data class MacroLibrary(
    val macros: StateFlow<List<WorkflowSummary>>,
    val editingId: String,
)

/**
 * The macro list, published to the config form the way the place and variable
 * libraries are — because `ConfigFieldEditor` renders a field knowing only its type,
 * and cannot be handed a macro-shaped parameter.
 *
 * Read-only, unlike those two, which is why this carries a list rather than a
 * ViewModel: a macro cannot be created from inside another macro's config form. That
 * would be a route *out* of the editor, and the deferred-pick idiom every picker
 * uses has nowhere to hand a half-built workflow.
 *
 * Null by default so previews and tests degrade to showing the raw id rather than
 * crashing, exactly as `LocalGeofencePlaces` and `LocalVariables` do.
 */
val LocalMacros = staticCompositionLocalOf<MacroLibrary?> { null }
