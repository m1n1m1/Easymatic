package io.github.m1n1m1.easymatic.feature.workflowlist

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import io.github.m1n1m1.easymatic.R
import io.github.m1n1m1.easymatic.domain.transfer.SetupNeed
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors
import io.github.m1n1m1.easymatic.feature.macro.editorTextButtonColors

/**
 * What an export or import just did, said once and read at leisure.
 *
 * A dialog rather than a snackbar, and that is the whole reason this composable
 * exists: a successful import has *three* things to report — the macro is in, these
 * library entries came with it, and these other things still have to be set up on this
 * phone — and none of them is glanceable. A macro that quietly needs a Hue bridge
 * paired before it will ever fire is exactly the failure this feature would otherwise
 * introduce, since everything about the graph looks perfect.
 */
@Composable
fun TransferDialog(message: TransferMessage, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = EditorColors.chrome,
        title = {
            Text(
                text = when (message) {
                    is TransferMessage.Imported -> stringResource(R.string.macro_transfer_imported_title)
                    is TransferMessage.Failed -> stringResource(failureTitle(message.reason))
                },
                color = EditorColors.textPrimary,
            )
        },
        text = {
            when (message) {
                is TransferMessage.Failed -> Unit
                is TransferMessage.Imported -> Column {
                    Line(stringResource(R.string.macro_transfer_imported_body, message.name))
                    // Each list is drawn only when it has something in it. A dialog
                    // that always shows "Still needs setting up: " with nothing after
                    // it reads as a bug, and the commonest import needs nothing.
                    val adopted = message.adopted.filter { it.isNotBlank() }
                    if (adopted.isNotEmpty()) {
                        Line(stringResource(R.string.macro_transfer_adopted, adopted.joinToString(", ")))
                    }
                    val needs = message.needsList()
                    if (needs.isNotEmpty()) {
                        Line(stringResource(R.string.macro_transfer_needs, needs.joinToString(", ")))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, colors = editorTextButtonColors()) { Text("OK") }
        },
    )
}

@Composable
private fun Line(text: String) {
    Text(text = text, color = EditorColors.textPrimary, fontSize = 14.sp)
}

/**
 * Everything the imported macro still needs from this phone, as one readable list.
 *
 * The three sources are joined here rather than shown as three sections because they
 * answer one question — *what do I have to go and do?* — and a plugin, an app and a
 * hub are all the same kind of answer to it.
 */
@Composable
private fun TransferMessage.Imported.needsList(): List<String> =
    needsPlugins.map { stringResource(R.string.macro_transfer_needs_plugin, it) } +
        needsApps.map { stringResource(R.string.macro_transfer_needs_app, it) } +
        needs.mapNotNull { need -> setupNeedLabel(need)?.let { stringResource(it) } }

/**
 * The wording for a [SetupNeed] key, or null for one this build does not know.
 *
 * Unknown keys are **skipped rather than shown raw**, which is the display half of the
 * decision to persist stable keys instead of sentences: a file written by a newer
 * Easymatic may name a requirement this one has never heard of, and `mqtt_broker_v2`
 * on screen is worse than one fewer line in an advisory list.
 */
private fun setupNeedLabel(need: String): Int? = when (need) {
    SetupNeed.AI_MODEL -> R.string.macro_transfer_need_ai_model
    SetupNeed.SMART_HOME_HUB -> R.string.macro_transfer_need_smart_home_hub
    SetupNeed.MAIL_ACCOUNT -> R.string.macro_transfer_need_mail_account
    SetupNeed.CALENDAR -> R.string.macro_transfer_need_calendar
    SetupNeed.SOUND -> R.string.macro_transfer_need_sound
    SetupNeed.CONTACT -> R.string.macro_transfer_need_contact
    else -> null
}

private fun failureTitle(reason: TransferFailure): Int = when (reason) {
    TransferFailure.UNREADABLE -> R.string.macro_transfer_unreadable
    TransferFailure.TOO_NEW -> R.string.macro_transfer_too_new
    TransferFailure.TOO_OLD -> R.string.macro_transfer_too_old
    TransferFailure.EXPORT_FAILED -> R.string.macro_transfer_export_failed
}
