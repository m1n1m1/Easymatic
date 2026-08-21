package com.example.ottomatic.feature.variables

import androidx.compose.ui.res.stringResource
import com.example.ottomatic.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ottomatic.feature.SettingsTopBar
import com.example.ottomatic.feature.grapheditor.EditorColors

/**
 * The standalone global variable library, reached from the workflow list.
 *
 * Globals are worth managing outside any particular macro for the same reason
 * geofence places are: an API key or a "quiet hours" flag belongs to the person,
 * not to whichever macro happened to declare it first. A workflow's *own*
 * variables have no screen here — they live in the editor's dock, beside the graph
 * that uses them.
 */
@Composable
fun GlobalVariablesScreen(
    viewModel: GlobalVariablesViewModel,
    onBack: () -> Unit,
) {
    val library = remember(viewModel) { GlobalOnlyLibrary(viewModel) }
    val globals by library.globals.collectAsState()
    var editing by remember { mutableStateOf<VariableEdit?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EditorColors.canvasBackground),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SettingsTopBar(
                title = stringResource(R.string.variables_global_variables),
                contentDescription = stringResource(R.string.variables_back),
                onBack = onBack,
            )

            VariableList(
                locals = emptyList(),
                globals = globals,
                showLocals = false,
                // Nothing to pick here, so selecting and editing are the same act.
                onSelect = { spec ->
                    library.resolve(spec)?.let { (scope, declaration) ->
                        editing = VariableEdit(scope, declaration)
                    }
                },
                onEdit = { scope, declaration -> editing = VariableEdit(scope, declaration) },
                onCreate = { editing = VariableEdit(VariableScope.GLOBAL, declaration = null) },
                modifier = Modifier.navigationBarsPadding(),
            )
        }
    }

    VariableEditorHost(library = library, edit = editing, onClose = { editing = null })
}
