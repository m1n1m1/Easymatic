package com.example.ottomatic.feature.variables

import com.example.ottomatic.domain.registry.ConfigOption
import com.example.ottomatic.feature.i18n.rememberNodeText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.example.ottomatic.domain.model.VariableDeclaration
import com.example.ottomatic.domain.model.VariableRef
import com.example.ottomatic.domain.model.config.ValueType
import com.example.ottomatic.domain.registry.enumConfigOptions
import kotlinx.coroutines.flow.StateFlow

/** Which set a declaration belongs to. Fixed when it is created — see [VariableEditorOverlay]. */
enum class VariableScope {
    /** This workflow's own, stored in its `Workflow.variables`. */
    LOCAL,

    /** The shared library, stored in `{filesDir}/variables/globals.json`. */
    GLOBAL,
}

/**
 * Both variable sets, and the four things anything does to them.
 *
 * Locals live in a workflow and globals in a repository, but every surface that
 * shows variables — the picker inside a node's config, the editor's dock, the
 * standalone globals screen — wants them side by side, so one interface covers
 * both and each host supplies what it has.
 *
 * Exposes `StateFlow`s rather than lists so that *providing* the library costs no
 * `collectAsState` at the provider: the graph editor publishes this once around the
 * whole screen, and only the composables that actually show variables recompose
 * when one changes. Hoisting the collection up to the provider would repaint the
 * canvas every time a variable's value ticked.
 */
interface VariableLibrary {

    /** This workflow's own declarations; always empty on the standalone globals screen. */
    val locals: StateFlow<List<VariableDeclaration>>

    /** The shared declarations. */
    val globals: StateFlow<List<VariableDeclaration>>

    /** False where there is no workflow in scope, which hides the local section entirely. */
    val supportsLocals: Boolean

    /** Creates or replaces [declaration] in [scope]. */
    fun upsert(scope: VariableScope, declaration: VariableDeclaration)

    /** Removes the declaration [id] from [scope]. References to it become a warning, not an error. */
    fun delete(scope: VariableScope, id: String)
}

/**
 * The library, made available to the parts of the editor that are too deep to be
 * handed a ViewModel.
 *
 * Mirrors [com.example.ottomatic.feature.geofence.LocalGeofencePlaces] and exists
 * for the same reason: the config form renders whatever the node schema says,
 * several composables below the screen that owns the ViewModels, so a `@Picker`
 * field needs the variable list without any of its callers knowing that variables
 * exist. Threading a parameter for that through every generic config composable
 * would make the whole form know about one node type.
 *
 * Null when no provider is in scope (previews, tests): callers fall back to showing
 * the raw ref rather than crashing.
 */
val LocalVariables = staticCompositionLocalOf<VariableLibrary?> { null }

/** The declaration [spec] refers to, searched in whichever set the spec names. */
fun VariableLibrary.resolve(spec: String): Pair<VariableScope, VariableDeclaration>? =
    when (val ref = VariableRef.parse(spec)) {
        null -> null
        is VariableRef.Local -> locals.value.firstOrNull { it.id == ref.id }?.let { VariableScope.LOCAL to it }
        is VariableRef.Global -> globals.value.firstOrNull { it.id == ref.id }?.let { VariableScope.GLOBAL to it }
    }

/** The ref spec that points at [declaration] in [scope]. */
fun specFor(scope: VariableScope, declaration: VariableDeclaration): String = when (scope) {
    VariableScope.LOCAL -> VariableRef.localSpec(declaration.id)
    VariableScope.GLOBAL -> VariableRef.globalSpec(declaration.id)
}

/**
 * "Whole number", "Date & time" — the labels a config form already shows for a
 * [ValueType], read off the same annotations rather than written out again here.
 * `enumConfigOptions` is exposed for exactly this: spelling "Date & time" twice is
 * how the two spellings start to differ.
 */
private val TYPE_LABELS: Map<ValueType, String> =
    enumConfigOptions(ValueType.serializer().descriptor).associate { option ->
        ValueType.valueOf(option.value) to option.label
    }

/**
 * The user-facing name of this type.
 *
 * Routed through [NodeText] so it uses the same `valuetype_*` key the config form's
 * type dropdown does — the alternative is the same word translated twice and
 * eventually differently.
 */
@Composable
fun ValueType.label(): String = rememberNodeText().valueTypeLabel(
    ConfigOption(value = name, label = TYPE_LABELS[this] ?: name),
)
