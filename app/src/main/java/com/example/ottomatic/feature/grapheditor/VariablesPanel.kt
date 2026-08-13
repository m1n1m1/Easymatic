package com.example.ottomatic.feature.grapheditor

import androidx.compose.ui.res.stringResource
import com.example.ottomatic.R
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.example.ottomatic.domain.model.VariableDeclaration
import com.example.ottomatic.domain.model.VariableRef
import com.example.ottomatic.feature.variables.LocalVariables
import com.example.ottomatic.feature.variables.VariableEdit
import com.example.ottomatic.feature.variables.VariableEditorHost
import com.example.ottomatic.feature.variables.VariableLibrary
import com.example.ottomatic.feature.variables.VariableList
import com.example.ottomatic.feature.variables.VariableScope
import kotlinx.coroutines.flow.StateFlow

/**
 * The variables of one [scope] and what they hold right now.
 *
 * The values tick as a run writes them, which is the half that makes this a
 * debugger rather than a settings list — "the counter is stuck at 3" is a question
 * that was previously unanswerable without a cable and Logcat.
 *
 * **One scope at a time**, unlike the picker, which lists both. The editor's
 * Variables surface is about *this macro's* state; a workflow with two counters
 * should not read as though it had fifteen because the shared library is long. The
 * globals are one tap away through the row [VariableList] draws for
 * [onOpenGlobals], and that row carries their count, so they are represented here
 * without being listed here.
 *
 * One of the three surfaces [EditorBottomBar] swaps in for the canvas. Creating and
 * editing still open [VariableEditorHost]'s own full-screen overlay, because this
 * region stops above the bar and the keyboard would take most of what is left.
 * A variable created from here lands in [scope], which is the set you are looking at.
 */
@Composable
fun VariablesBody(
    variableValues: StateFlow<Map<String, String>>,
    scope: VariableScope,
    modifier: Modifier = Modifier,
    onOpenGlobals: (() -> Unit)? = null,
) {
    val library = LocalVariables.current
    if (library == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text =
                
                    
                        
                            stringResource(R.string.grapheditor_variables_are_unavailable_here),
                            color = EditorColors.textSecondary,
                            fontSize = 13.sp,
                        )
        }
        return
    }
    val locals by library.locals.collectAsState()
    val globals by library.globals.collectAsState()
    val values by variableValues.collectAsState()
    var editing by remember { mutableStateOf<VariableEdit?>(null) }
    // Nowhere to put a local one, so the local view degrades to the global set
    // rather than to an empty screen with a New button that cannot work.
    val showing = if (scope == VariableScope.LOCAL && !library.supportsLocals) VariableScope.GLOBAL else scope

    VariableList(
        locals = locals,
        globals = globals,
        showLocals = showing == VariableScope.LOCAL,
        showGlobals = showing == VariableScope.GLOBAL,
        onOpenGlobals = onOpenGlobals.takeIf { showing == VariableScope.LOCAL },
        values = values,
        onSelect = { spec ->
            when (val resolved = library.resolveIn(locals, globals, spec)) {
                null -> Unit
                else -> editing = VariableEdit(resolved.first, resolved.second)
            }
        },
        onEdit = { editScope, declaration -> editing = VariableEdit(editScope, declaration) },
        onCreate = { editing = VariableEdit(scope = showing, declaration = null) },
        modifier = modifier,
    )

    VariableEditorHost(library = library, edit = editing, onClose = { editing = null })
}

/**
 * Resolves a spec against lists already collected here.
 *
 * The library's own `resolve` reads `StateFlow.value`, which would be a read the
 * composition does not observe; using what was collected keeps the row and the
 * editor looking at the same snapshot.
 */
private fun VariableLibrary.resolveIn(
    locals: List<VariableDeclaration>,
    globals: List<VariableDeclaration>,
    spec: String,
): Pair<VariableScope, VariableDeclaration>? {
    val ref = VariableRef.parse(spec) ?: return null
    return when (ref) {
        is VariableRef.Local -> locals.firstOrNull { it.id == ref.id }?.let { VariableScope.LOCAL to it }
        is VariableRef.Global -> globals.firstOrNull { it.id == ref.id }?.let { VariableScope.GLOBAL to it }
    }
}
