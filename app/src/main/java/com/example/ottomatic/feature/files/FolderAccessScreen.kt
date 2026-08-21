package com.example.ottomatic.feature.files

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import com.example.ottomatic.R
import com.example.ottomatic.data.files.GrantedFile
import com.example.ottomatic.feature.SettingsTopBar
import com.example.ottomatic.feature.grapheditor.EditorColors

/**
 * What Ottomatic is allowed to reach on the user's storage.
 *
 * The counterpart to the Permissions screen and deliberately **not** part of it: every
 * entry there is one system-wide switch with its own settings page, which is what makes
 * a Grant button and a green row meaningful. A folder grant is neither system-wide nor
 * reachable from any settings page, so a row for it there could never turn green.
 *
 * It is also where the honest limit gets explained. Android refuses a grant on the root
 * of internal storage, on the `Download` folder itself and on memory-card roots — the
 * chooser simply declines, which reads as the app being broken unless somebody has said
 * otherwise first. That is what the empty state is for.
 */
@Composable
fun FolderAccessScreen(
    viewModel: FolderAccessViewModel,
    onBack: () -> Unit,
) {
    val folders by viewModel.folders.collectAsState()
    val files by viewModel.files.collectAsState()
    var unusable by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Every grant on this screen is obtained by leaving the app, so returning to it is
    // exactly when the answer has changed — `MainActivity.onResume`'s reasoning.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { picked ->
        if (picked != null) viewModel.add(picked)
    }

    // Several at once, because the case this exists for — files already sitting in
    // Download — is rarely about exactly one of them.
    val fileChooser = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { picked ->
        if (picked.isNotEmpty()) unusable = viewModel.addFiles(picked)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EditorColors.canvasBackground),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SettingsTopBar(
                title = stringResource(R.string.files_folder_access),
                contentDescription = stringResource(R.string.nfc_back),
                onBack = onBack,
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (folders.isEmpty() && files.isEmpty()) {
                    item { EmptyState() }
                }
                items(folders, key = { it.folder.treeUri.toString() }) { row ->
                    FolderCard(row = row, onRevoke = { viewModel.revoke(row.folder) })
                }
                if (files.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.files_files_heading),
                            color = EditorColors.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                        )
                    }
                }
                items(files, key = { it.uri.toString() }) { file ->
                    FileCard(file = file, onRevoke = { viewModel.revokeFile(file) })
                }
                if (unusable > 0) {
                    item {
                        Text(
                            text = stringResource(R.string.files_no_path),
                            color = EditorColors.errorAccent,
                            fontSize = 13.sp,
                        )
                    }
                }
                item { DownloadNote() }
                item { OwnStorageNote() }
                item {
                    Button(onClick = { chooser.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.files_grant))
                    }
                }
                item {
                    OutlinedButton(
                        onClick = {
                            unusable = 0
                            fileChooser.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.files_add_file))
                    }
                }
            }
        }
    }
}

/**
 * The empty state, which carries the one thing somebody has to be told in advance.
 *
 * Without this, the first thing a new user does is open the chooser, tap internal
 * storage or Download, find "Use this folder" refuses, and conclude the feature is
 * broken. The platform gives no way to explain that from inside the chooser, so it has
 * to be said here.
 */
@Composable
private fun EmptyState() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.files_no_folders),
            color = EditorColors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.files_no_folders_hint),
            color = EditorColors.textSecondary,
            fontSize = 13.sp,
        )
    }
}

/**
 * The `Download` folder, which is the one place the ordinary advice does not work.
 *
 * Android refuses a tree grant on it, so "choose the folder" is an instruction that
 * cannot be carried out — the chooser simply declines, which reads as the app being
 * broken. Both things that *do* work are named here instead.
 */
@Composable
private fun DownloadNote() {
    Text(
        text = stringResource(R.string.files_download_note),
        color = EditorColors.textSecondary,
        fontSize = 13.sp,
    )
}

/** A reminder that macros can always write somewhere, with nothing granted at all. */
@Composable
private fun OwnStorageNote() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.files_own_storage),
            color = EditorColors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
        Text(
            text = stringResource(R.string.files_own_storage_hint),
            color = EditorColors.textSecondary,
            fontSize = 13.sp,
        )
    }
}

/**
 * One file granted on its own.
 *
 * No health check, unlike a folder: the two questions a folder needs are worth an IPC
 * each because a macro writes into it repeatedly, where a single read-only file that
 * has gone will report itself the next time a macro asks for it.
 */
@Composable
private fun FileCard(file: GrantedFile, onRevoke: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = EditorColors.chrome)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = EditorColors.textPrimary,
                )
                Text(
                    text = file.name,
                    color = EditorColors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(text = file.path, color = EditorColors.textSecondary, fontSize = 12.sp)
            Text(
                text = stringResource(R.string.files_read_only_file),
                color = EditorColors.textSecondary,
                fontSize = 12.sp,
            )
            TextButton(onClick = onRevoke) { Text(stringResource(R.string.files_revoke)) }
        }
    }
}

@Composable
private fun FolderCard(row: FolderRow, onRevoke: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = EditorColors.chrome)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (row.present) Icons.Filled.FolderOpen else Icons.Filled.FolderOff,
                    contentDescription = null,
                    tint = if (row.present) EditorColors.textPrimary else EditorColors.errorAccent,
                )
                Text(
                    text = row.folder.name,
                    color = EditorColors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(text = row.folder.path, color = EditorColors.textSecondary, fontSize = 12.sp)
            Text(
                text = stringResource(
                    if (row.folder.canWrite) R.string.files_read_write else R.string.files_read_only,
                ),
                color = EditorColors.textSecondary,
                fontSize = 12.sp,
            )
            // The case the whole screen exists for: the grant is held, the folder is
            // not there. A restored phone shows every row like this.
            if (!row.present) {
                Text(
                    text = stringResource(R.string.files_missing),
                    color = EditorColors.errorAccent,
                    fontSize = 12.sp,
                )
            }
            TextButton(onClick = onRevoke) { Text(stringResource(R.string.files_revoke)) }
        }
    }
}
