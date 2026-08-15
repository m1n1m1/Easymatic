package com.example.ottomatic.feature.files

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.example.ottomatic.R
import com.example.ottomatic.data.files.SafGrants
import com.example.ottomatic.data.files.StorageVolumes

/**
 * A `@FilePath` field: a path the user can type, with a button that opens the
 * system's chooser.
 *
 * The fifth of the editable-with-a-chooser fields, and the one whose chooser does
 * **two jobs rather than one** — it takes the persistable access grant *and* fills the
 * path in. That is what makes typing viable afterwards: choose a folder once and every
 * path inside it can be typed, built with `transform.text`, or arrive down a wire,
 * forever and from the background service.
 *
 * **Two chooser entries, because Android has two and they are not interchangeable.**
 * *Choose a file* grants exactly one file, which is right for a read and useless for a
 * write — the file being written usually does not exist, and `ACTION_OPEN_DOCUMENT`
 * cannot name a thing that is not there. *Choose a folder* grants the folder and
 * everything in it, fills in the folder's path, and leaves the filename to be typed
 * after it. The second is the one to reach for and is listed first for that reason;
 * offering only it would make reading a single existing file needlessly broad.
 *
 * The field itself stays **editable at all times**, unlike the contact picker's, which
 * locks once a contact is chosen. There is no spec here to corrupt — the text is the
 * path — so a folder filled in by the chooser is *meant* to be typed after.
 */
@Composable
fun FilePathField(
    value: String,
    onValueChange: (String) -> Unit,
    labelSlot: @Composable () -> Unit,
    colors: TextFieldColors,
) {
    val context = LocalContext.current
    var choosing by remember { mutableStateOf(false) }

    // A chosen file that has no path on this device. Reported rather than swallowed:
    // before this the field simply did not change, which reads as the button being dead.
    var unreachable by remember { mutableStateOf(false) }

    // A folder grant covers everything inside it, so this is the one that makes a
    // macro's typed or wired paths work afterwards.
    val folders = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { picked ->
        if (picked == null) return@rememberLauncherForActivityResult
        SafGrants.persist(context, picked, writeIntent())
        val path = StorageVolumes.pathOf(context, picked) ?: return@rememberLauncherForActivityResult
        // A trailing separator, so the next thing typed is a name inside the folder
        // rather than a sibling of it.
        onValueChange("$path/")
    }

    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked == null) return@rememberLauncherForActivityResult
        val path = SafGrants.pathOfDocument(context, picked)
        if (path == null) {
            // A file with no path on this device — one from a cloud provider, or a
            // download the provider names by id rather than by where it sits. The grant
            // is given back rather than kept: nothing could ever address it, and an
            // unusable grant still counts against the platform's per-app cap.
            unreachable = true
            return@rememberLauncherForActivityResult
        }
        unreachable = false
        SafGrants.persist(context, picked, readIntent())
        onValueChange(path)
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = labelSlot,
        colors = colors,
        placeholder = {
            Text(
                text = stringResource(R.string.files_path_placeholder),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        isError = unreachable,
        supportingText = if (unreachable) {
            { Text(stringResource(R.string.files_no_path)) }
        } else {
            null
        },
        trailingIcon = {
            IconButton(onClick = { choosing = true }) {
                Icon(
                    imageVector = Icons.Filled.FolderOpen,
                    contentDescription = stringResource(R.string.files_choose),
                )
            }
            DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.files_choose_folder)) },
                    leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                    onClick = {
                        choosing = false
                        folders.launch(null)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.files_choose_file)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null) },
                    onClick = {
                        choosing = false
                        files.launch(arrayOf("*/*"))
                    },
                )
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The flags a tree chooser conveys, as [SafGrants.persist] expects to read them. */
private fun writeIntent(): Intent = Intent().addFlags(
    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
)

/** A single document is granted for reading; `ACTION_OPEN_DOCUMENT` conveys no more. */
private fun readIntent(): Intent = Intent().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
