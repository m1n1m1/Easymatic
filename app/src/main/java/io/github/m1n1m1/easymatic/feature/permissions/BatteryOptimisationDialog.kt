package io.github.m1n1m1.easymatic.feature.permissions

import androidx.compose.ui.res.stringResource
import io.github.m1n1m1.easymatic.R
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors

/**
 * Offered after a reboot where the engine could not start itself, to ask for the
 * one thing that usually fixes it.
 *
 * It lives here rather than inside the graph editor, which is where it used to
 * be. The flag that raises it is consumed in `onResume` — normally on the
 * workflow list, where there was nothing to draw it — so it sat latched until
 * the user happened to open a macro, and then appeared over the canvas as if
 * opening *that* macro had caused it. A prompt about the app as a whole belongs
 * over whatever the user is looking at.
 *
 * It is a prompt and not the durable answer: the durable answer is the row on
 * [PermissionsScreen], which says the same thing whether or not a boot has
 * failed recently.
 */
@Composable
fun BatteryOptimisationDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.permissions_disable_battery_optimisation)) },
        text = {
            Text(
                stringResource(R.string.permissions_easymatic_couldn_t_resume_your),
                color = EditorColors.textPrimary,
                fontSize = 14.sp,
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.permissions_allow)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.permissions_not_now)) } },
    )
}
