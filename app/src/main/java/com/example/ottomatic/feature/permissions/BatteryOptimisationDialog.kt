package com.example.ottomatic.feature.permissions

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import com.example.ottomatic.feature.grapheditor.EditorColors

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
        title = { Text("Disable battery optimisation") },
        text = {
            Text(
                "Ottomatic couldn't resume your macros in the background after the last " +
                    "reboot. To keep automation running without intervention, allow Ottomatic " +
                    "to run without battery restrictions. You can change this later under " +
                    "Permissions.",
                color = EditorColors.textPrimary,
                fontSize = 14.sp,
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Allow") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
