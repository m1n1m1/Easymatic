package com.example.ottomatic.feature.macro

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.example.ottomatic.R
import com.example.ottomatic.feature.grapheditor.EditorColors

/**
 * Everything that can be done to a macro *as a whole*, in the one menu that offers it.
 *
 * There are two places a macro is looked at — its row on the list and its own editor —
 * and until now each had grown its own overflow menu: six items on the row, two in the
 * editor. Which is worse than it sounds, because the editor is where somebody is when
 * they decide a macro is finished and want to send it to a friend, and the item was
 * only on the screen they had already left. The two menus are one composable now, so
 * "the actions differ depending on where you opened them from" cannot come back — the
 * next item is added here and appears in both.
 *
 * Two things are deliberately *not* in it. **Arming** is a switch beside the menu in
 * both places, because it is the one thing a user flips repeatedly and it has to say
 * its state without being opened. **Import** belongs to the list and not to a macro at
 * all — it is where a macro comes *from*, so it sits by the list's Add button rather
 * than inside a menu hanging off some unrelated macro.
 *
 * The callbacks are what the two screens genuinely differ on: the list acts on the row
 * it was opened from, the editor on the graph it is showing — which additionally has to
 * be written to disk before anything reads it back. Neither difference is visible here.
 */
@Composable
fun MacroActionsMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    /**
     * Whether the macro has a manual trigger to put on the home screen. A macro with
     * none has no button to pin, and an item that explains that *after* being tapped
     * is worse than an item that is not there.
     */
    canPin: Boolean,
    onEdit: () -> Unit,
    onPin: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest) {
        // "Edit…" rather than "Edit", the way Export and Share are worded: all three
        // open something and ask for more before anything happens. Delete does not get
        // the ellipsis — its dialog asks nothing, it only confirms.
        Item(R.string.grapheditor_edit, Icons.Filled.Edit, onDismissRequest, onEdit)
        if (canPin) {
            Item(
                R.string.workflowlist_add_to_home_screen,
                Icons.AutoMirrored.Filled.AddToHomeScreen,
                onDismissRequest,
                onPin,
            )
        }
        Item(R.string.macro_transfer_duplicate, Icons.Filled.ContentCopy, onDismissRequest, onDuplicate)
        Item(R.string.macro_transfer_export, Icons.Filled.FileUpload, onDismissRequest, onExport)
        Item(R.string.macro_transfer_share, Icons.Filled.Share, onDismissRequest, onShare)
        // The one destructive item, and the only one tinted: a menu where nothing
        // stands out is a menu where Delete sits a thumb's width from Duplicate.
        Item(
            R.string.workflowlist_delete,
            Icons.Filled.Delete,
            onDismissRequest,
            onDelete,
            tint = EditorColors.errorAccent,
        )
    }
}

/**
 * One row of the menu, which always closes it before acting.
 *
 * Closing first rather than in each callback: every one of these opens a dialog, starts
 * a system chooser or leaves the screen, and a menu still standing over a document
 * picker is a menu that reopens on top of whatever comes back.
 */
@Composable
private fun Item(
    @StringRes textRes: Int,
    icon: ImageVector,
    onDismissRequest: () -> Unit,
    onClick: () -> Unit,
    tint: Color? = null,
) {
    DropdownMenuItem(
        text = {
            if (tint == null) Text(stringResource(textRes)) else Text(stringResource(textRes), color = tint)
        },
        onClick = {
            onDismissRequest()
            onClick()
        },
        leadingIcon = {
            if (tint == null) {
                Icon(icon, contentDescription = null)
            } else {
                Icon(icon, contentDescription = null, tint = tint)
            }
        },
    )
}
