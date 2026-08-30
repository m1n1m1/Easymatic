package io.github.m1n1m1.easymatic.feature.macro

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.m1n1m1.easymatic.R
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors
import io.github.m1n1m1.easymatic.feature.widget.ManualTriggerRef

/** Puts one of a macro's manual triggers on the home screen, asking first if it must. */
class MacroPinner internal constructor(private val onPin: (List<ManualTriggerRef>) -> Unit) {
    /** Places [triggers]' button, or asks which one when there is more than one. */
    fun pin(triggers: List<ManualTriggerRef>) = onPin(triggers)
}

/**
 * The whole "Add to home screen" flow — the decision and both dialogs it can raise —
 * as one call.
 *
 * Composed rather than passed around because two of its three outcomes are UI: one
 * trigger is not a choice, so placing goes straight to the launcher's own confirmation,
 * which is the only prompt that decision actually needs; several is a question, and a
 * launcher that refuses to place anything at all is a third answer that has to be
 * reported rather than waited on.
 *
 * Both dialogs are drawn from here, so a screen offering the item does not also have to
 * carry two pieces of state and two `AlertDialog`s it has nothing to say about.
 */
@Composable
fun rememberMacroPinner(pin: (ManualTriggerRef) -> Boolean): MacroPinner {
    var choosing by remember { mutableStateOf<List<ManualTriggerRef>?>(null) }
    var refused by remember { mutableStateOf(false) }

    // Read through a state holder rather than captured: the object below is remembered
    // once, and [pin] is a fresh lambda on every recomposition.
    val current = rememberUpdatedState(pin)

    choosing?.let { triggers ->
        // Only ever shown for a macro with more than one manual trigger: the question
        // is which button to pin, and a macro with one has no such question.
        AlertDialog(
            onDismissRequest = { choosing = null },
            containerColor = EditorColors.chrome,
            title = { Text(stringResource(R.string.workflowlist_which_trigger), color = EditorColors.textPrimary) },
            text = {
                Column {
                    triggers.forEach { trigger ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    choosing = null
                                    if (!pin(trigger)) refused = true
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MacroIconChip(icon = trigger.icon, accent = trigger.accent)
                            Spacer(Modifier.width(12.dp))
                            Text(trigger.label, color = EditorColors.textPrimary, fontSize = 15.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { choosing = null }, colors = editorTextButtonColors()) {
                    Text(stringResource(R.string.workflowlist_cancel))
                }
            },
        )
    }

    if (refused) {
        AlertDialog(
            onDismissRequest = { refused = false },
            containerColor = EditorColors.chrome,
            title = { Text(stringResource(R.string.workflowlist_can_t_add_it_from), color = EditorColors.textPrimary) },
            text = {
                Text(
                    stringResource(R.string.workflowlist_launcher_cannot_place),
                    color = EditorColors.textPrimary,
                    fontSize = 14.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { refused = false }, colors = editorTextButtonColors()) { Text("OK") }
            },
        )
    }

    return remember {
        MacroPinner { triggers ->
            when {
                triggers.isEmpty() -> Unit
                triggers.size == 1 -> if (!current.value(triggers.first())) refused = true
                else -> choosing = triggers
            }
        }
    }
}
