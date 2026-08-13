package com.example.ottomatic.feature.variables

import androidx.compose.ui.res.stringResource
import com.example.ottomatic.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            Surface(color = EditorColors.chrome) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(60.dp)
                        .padding(start = 6.dp, end = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.variables_back),
                            tint = EditorColors.textPrimary,
                        )
                    }
                    Text(
                        text = stringResource(R.string.variables_global_variables),
                        color = EditorColors.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }

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
