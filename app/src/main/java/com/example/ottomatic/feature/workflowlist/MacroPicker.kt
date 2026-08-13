package com.example.ottomatic.feature.workflowlist

import androidx.compose.ui.res.stringResource
import com.example.ottomatic.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ottomatic.domain.model.WorkflowSummary
import com.example.ottomatic.feature.grapheditor.EditorColors
import com.example.ottomatic.feature.grapheditor.EditorOverlay

private val ROW_SHAPE = RoundedCornerShape(16.dp)

/**
 * The macro chooser a `@Picker(MACRO)` field opens.
 *
 * Follows [com.example.ottomatic.feature.geofence.GeofencePlacePickerOverlay]'s
 * deferred-pick idiom — the tap records the choice and closes, [onPick] runs after
 * the exit animation — and has no "new macro" row, because a macro cannot be
 * created from inside another one's config form.
 *
 * The workflow being edited is **listed and marked**, not hidden: see [MacroLibrary].
 */
@Composable
fun MacroPickerOverlay(
    macros: List<WorkflowSummary>,
    selectedId: String?,
    editingId: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf<String?>(null) }

    EditorOverlay(
        title = stringResource(R.string.workflowlist_choose_a_macro),
        onClose = { picked?.let(onPick) ?: onDismiss() },
    ) { dismiss ->
        Column(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (macros.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = stringResource(R.string.workflowlist_no_other_macros_yet),
                            style = MaterialTheme.typography.bodyMedium,
                            color = EditorColors.textSecondary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
                        )
                    }
                }
                items(macros, key = { it.id }) { macro ->
                    MacroRow(
                        macro = macro,
                        selected = macro.id == selectedId,
                        isSelf = macro.id == editingId,
                        onClick = {
                            picked = macro.id
                            dismiss()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MacroRow(
    macro: WorkflowSummary,
    selected: Boolean,
    isSelf: Boolean,
    onClick: () -> Unit,
) {
    val accent = EditorColors.triggerAccent
    val borderColor = if (selected) accent else EditorColors.nodeBorder
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ROW_SHAPE)
            .background(EditorColors.nodeBackground)
            .border(if (selected) 2.dp else 1.dp, borderColor, ROW_SHAPE)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.AccountTree,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = macro.name,
                style = MaterialTheme.typography.bodyLarge,
                color = EditorColors.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // Whether it is armed is the other thing worth knowing here, since
                // enabling something already enabled is a no-op the user should be
                // able to see coming. "this macro" comes first: pointing a node at
                // its own workflow is deliberate when it is deliberate, and a
                // surprise otherwise.
                text = listOfNotNull(
                    stringResource(R.string.workflowlist_this_macro).takeIf { isSelf },
                    if (macro.enabled) "enabled" else "disabled",
                ).joinToString(stringResource(R.string.workflowlist_text)),
                style = MaterialTheme.typography.bodySmall,
                color = EditorColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
