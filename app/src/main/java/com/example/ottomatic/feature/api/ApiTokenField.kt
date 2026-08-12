package com.example.ottomatic.feature.api

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.example.ottomatic.domain.model.ApiTokens

/**
 * An `@ApiToken` field: the generated key a `trigger.api` carries, shown read-only
 * with Copy, Regenerate and Clear beside it.
 *
 * **Read-only, unlike every other editable-with-a-chooser field**, and for the
 * opposite reason a `@Picker` is: those are read-only because the value must be one
 * of a set the node cannot let the user stray from, where this one is read-only
 * because a *typed* key is strictly worse than a generated one at the only job it
 * has. Anything a person invents is shorter and more guessable than 192 bits, and a
 * key mistyped by one character fails exactly as a stolen one does — which is the
 * worst possible pair of failures to be unable to tell apart.
 *
 * **Blank is offered rather than prevented.** Clearing the field is how a macro says
 * *approved apps only*, which is the stricter setting and the right one for anything
 * that does not need to be reachable from a shell script. So the placeholder states
 * what blank means instead of reading as an empty required field, and nothing in the
 * Problems panel objects to it.
 *
 * The value is not hidden the way a password is: the user is the one person entitled
 * to it, and the whole point of the field is to get it out of here and into another
 * app. What it *is* marked as is sensitive **on the clipboard**, so Android 13's
 * paste preview does not print it across the screen.
 */
@Composable
fun ApiTokenField(
    value: String,
    onValueChange: (String) -> Unit,
    labelSlot: @Composable () -> Unit,
    colors: TextFieldColors,
) {
    val context = LocalContext.current
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        label = labelSlot,
        colors = colors,
        placeholder = {
            Text(text = "No key — approved apps only", maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingIcon = {
            Row {
                if (value.isNotBlank()) {
                    IconButton(onClick = { copyToClipboard(context, value) }) {
                        Icon(imageVector = Icons.Filled.ContentCopy, contentDescription = "Copy the key")
                    }
                }
                IconButton(onClick = { onValueChange(ApiTokens.generate()) }) {
                    Icon(
                        imageVector = Icons.Filled.Autorenew,
                        contentDescription = if (value.isBlank()) "Generate a key" else "Replace the key",
                    )
                }
                if (value.isNotBlank()) {
                    IconButton(onClick = { onValueChange("") }) {
                        Icon(imageVector = Icons.Filled.Clear, contentDescription = "Remove the key")
                    }
                }
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Copies [token], flagged sensitive so the system's paste preview blanks it.
 *
 * The toast is only raised below API 33, where the platform shows no confirmation of
 * its own — above it, one would be a second popup saying what the first already
 * said.
 */
private fun copyToClipboard(context: Context, token: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText("Ottomatic key", token).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
    }
    clipboard.setPrimaryClip(clip)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "Key copied", Toast.LENGTH_SHORT).show()
    }
}
