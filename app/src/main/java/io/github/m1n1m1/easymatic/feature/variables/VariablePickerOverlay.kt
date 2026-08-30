package io.github.m1n1m1.easymatic.feature.variables

import androidx.compose.ui.res.stringResource
import io.github.m1n1m1.easymatic.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.m1n1m1.easymatic.domain.model.VariableDeclaration
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorOverlay

/**
 * Picks a variable for a `@Picker(PickerKind.VARIABLE)` config field.
 *
 * Creating and editing are reachable from here rather than only from the dock, for
 * the reason the geofence picker gives: the moment you realise a variable is
 * missing, or is the wrong type, is while wiring the node that needs it, and
 * sending the user somewhere else to fix it would lose the node they were
 * configuring.
 *
 * Follows the deferred-pick idiom — the choice is held until the exit animation has
 * played — so picking a variable feels the same as picking a node or a place.
 */
@Composable
fun VariablePickerOverlay(
    library: VariableLibrary,
    selectedSpec: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val locals by library.locals.collectAsState()
    val globals by library.globals.collectAsState()
    var picked by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<VariableEdit?>(null) }

    EditorOverlay(
        title = stringResource(R.string.variables_choose_a_variable),
        onClose = { picked?.let(onPick) ?: onDismiss() },
    ) { dismiss ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            VariableList(
                locals = locals,
                globals = globals,
                selectedSpec = selectedSpec,
                showLocals = library.supportsLocals,
                onSelect = { spec ->
                    picked = spec
                    dismiss()
                },
                onEdit = { scope, declaration -> editing = VariableEdit(scope, declaration) },
                onCreate = {
                    editing = VariableEdit(
                        scope = if (library.supportsLocals) VariableScope.LOCAL else VariableScope.GLOBAL,
                        declaration = null,
                    )
                },
            )
        }
    }

    // Stacked above the picker. A variable created here is selected on save: the
    // user opened the picker to choose one, and having just declared it they should
    // not have to find it in the list afterwards.
    VariableEditorHost(
        library = library,
        edit = editing,
        onClose = { editing = null },
        onSaved = { spec, wasNew -> if (wasNew) onPick(spec) },
    )
}

/** What the editor overlay is open on: a scope, and the declaration being changed (null = new). */
data class VariableEdit(val scope: VariableScope, val declaration: VariableDeclaration?)

/**
 * Mounts [VariableEditorOverlay] for an open [edit] and writes the result back to
 * [library].
 *
 * Extracted because all three hosts — the picker, the editor's dock tab and the
 * standalone globals screen — open the same editor on the same two cases, and
 * repeating the save/delete wiring three times is three chances for them to differ.
 */
@Composable
fun VariableEditorHost(
    library: VariableLibrary,
    edit: VariableEdit?,
    onClose: () -> Unit,
    onSaved: (spec: String, wasNew: Boolean) -> Unit = { _, _ -> },
) {
    if (edit == null) return
    VariableEditorOverlay(
        initial = edit.declaration,
        scope = edit.scope,
        // Only on creation: a ref records which set its variable is in, so moving
        // one afterwards would mean rewriting every node that points at it.
        canChooseScope = edit.declaration == null && library.supportsLocals,
        onSave = { scope, declaration ->
            library.upsert(scope, declaration)
            onClose()
            onSaved(specFor(scope, declaration), edit.declaration == null)
        },
        onDelete = edit.declaration?.let { declaration ->
            {
                library.delete(edit.scope, declaration.id)
                onClose()
            }
        },
        onDismiss = onClose,
    )
}
