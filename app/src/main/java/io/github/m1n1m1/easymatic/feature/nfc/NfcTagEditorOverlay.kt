package io.github.m1n1m1.easymatic.feature.nfc

import androidx.compose.ui.res.stringResource
import io.github.m1n1m1.easymatic.R
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.m1n1m1.easymatic.core.permissions.PrerequisiteType
import io.github.m1n1m1.easymatic.data.nfc.NfcReader
import io.github.m1n1m1.easymatic.domain.model.NfcTagId
import io.github.m1n1m1.easymatic.feature.findActivity
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorOverlay
import io.github.m1n1m1.easymatic.feature.permissions.openSettingsFor

/**
 * Captures a tag and names it, or renames one that is already saved.
 *
 * The capture half is the reason this is not the two-field form it looks like. A
 * tag that has never been scanned is in no list, so unlike every other chooser in
 * the app the option set here has to be *produced* rather than browsed: the
 * overlay opens with nothing, waits for the user to hold a tag to the back of the
 * phone, and only then has something to name.
 *
 * While it is open it puts the platform into reader mode, which routes taps here
 * and **suppresses the manifest dispatch entirely** — so scanning a tag that a
 * macro is armed for cannot also run that macro. Setting a trigger up is not the
 * same act as firing it.
 */
@Composable
fun NfcTagEditorOverlay(
    draft: NfcTagDraft,
    viewModel: NfcTagsViewModel,
    onClose: () -> Unit,
    onSaved: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val available = remember(context) { NfcReader.isAvailable(context) }
    // Re-read on every resume rather than once: switching NFC on is done in system
    // Settings, so the user leaves and comes back, and a state captured on first
    // composition would still be saying "NFC is off" after they had fixed it.
    val enabled = rememberNfcEnabled(context)

    // Reader mode belongs to the Activity, not to this overlay — but an
    // EditorOverlay is a Dialog, which does not pause the Activity behind it, so
    // registering from here works and stays live. onDispose is not optional: the
    // registration outlives this composable, and skipping it would leave the app
    // swallowing every tag tap for the rest of the process.
    DisposableEffect(activity, draft.capture, enabled) {
        if (activity != null && draft.capture && enabled) {
            NfcReader.enableReaderMode(activity) { tag ->
                // Arrives on a binder thread. Going through the ViewModel is what
                // marshals it: MutableStateFlow is safe to write from anywhere.
                val scan = NfcReader.fromTag(tag)
                viewModel.tagScanned(scan.uid, scan.techs, scan.text)
            }
        }
        onDispose { activity?.let(NfcReader::disableReaderMode) }
    }

    EditorOverlay(
        title = if (draft.capture) stringResource(R.string.nfc_scan_a_tag) else stringResource(R.string.nfc_rename_tag),
        onClose = onClose,
        action = {
            if (!draft.isNew) {
                IconButton(onClick = { viewModel.delete(draft.uid) }) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.nfc_delete_tag),
                        tint = EditorColors.textSecondary,
                    )
                }
            }
            TextButton(
                onClick = { viewModel.save(onSaved) },
                enabled = draft.canSave,
            ) {
                Text(stringResource(R.string.nfc_save))
            }
        },
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!draft.scanned) {
                when {
                    !available -> NfcNotice(
                        title = stringResource(R.string.nfc_this_phone_has_no_nfc),
                        body = stringResource(R.string.nfc_there_is_nothing_to_scan),
                    )
                    !enabled -> NfcNotice(
                        title = stringResource(R.string.nfc_nfc_is_switched_off),
                        body = stringResource(R.string.nfc_easymatic_cannot_switch_it_on),
                        onOpenSettings = { context.openSettingsFor(PrerequisiteType.NFC) },
                    )
                    else -> WaitingForTag()
                }
                return@Column
            }
            ScannedTag(draft)
            OutlinedTextField(
                value = draft.name,
                onValueChange = viewModel::draftNameChanged,
                label = { Text(stringResource(R.string.nfc_name)) },
                placeholder = { Text(stringResource(R.string.nfc_desk_car_dock_nightstand)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // Only from the library, never from the capture overlay a picker opens.
            // Two reasons: reader mode is already registered for capture there and
            // two registrations would fight, and writing has nothing to do with
            // choosing a tag for a trigger — it changes nothing the trigger reads.
            if (!draft.capture) {
                TextButton(onClick = viewModel::startWrite) { Text(
                    stringResource(R.string.nfc_write_data_to_this_tag)) }
            }
        }
    }
}

/**
 * Whether NFC is on, re-read on every resume.
 *
 * Grant state is not snapshot-observable and this one is changed *outside the app*
 * — the notice below sends the user to Settings — so the same `ON_RESUME` revision
 * counter `rememberPermissionState` uses is what makes coming back show the truth.
 */
@Composable
private fun rememberNfcEnabled(context: Context): Boolean {
    var revision by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return remember(context, revision) { NfcReader.isEnabled(context) }
}

@Composable
private fun NfcNotice(title: String, body: String, onOpenSettings: (() -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = EditorColors.textPrimary,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = EditorColors.textSecondary,
        )
        onOpenSettings?.let { Button(onClick = it) { Text(stringResource(R.string.nfc_open_settings)) } }
    }
}

@Composable
private fun WaitingForTag() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Nfc,
                contentDescription = null,
                tint = EditorColors.triggerAccent,
                modifier = Modifier.size(56.dp),
            )
            Text(
                text = stringResource(R.string.nfc_hold_the_tag_to_the),
                style = MaterialTheme.typography.titleMedium,
                color = EditorColors.textPrimary,
            )
            Text(
                text = stringResource(R.string.nfc_the_nfc_antenna_is_usually),
                style = MaterialTheme.typography.bodyMedium,
                color = EditorColors.textSecondary,
            )
            CircularProgressIndicator(color = EditorColors.triggerAccent)
        }
    }
}

@Composable
private fun ScannedTag(draft: NfcTagDraft) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = NfcTagId.display(draft.uid),
            style = MaterialTheme.typography.titleMedium,
            color = EditorColors.textPrimary,
            fontFamily = FontFamily.Monospace,
        )
        if (draft.text.isNotBlank()) {
            Text(
                text = stringResource(R.string.nfc_tag_says, draft.text),
                style = MaterialTheme.typography.bodyMedium,
                color = EditorColors.textSecondary,
            )
        }
        if (!draft.isNew) {
            Text(
                text = stringResource(R.string.nfc_already_in_your_library),
                style = MaterialTheme.typography.bodySmall,
                color = EditorColors.textSecondary,
            )
        }
        // A warning rather than a refusal, because the read itself is real and the
        // user is entitled to see what came back. Saving is what is blocked, since
        // an id that changes on every tap can never be matched against.
        if (draft.unstable) {
            Text(
                text = stringResource(R.string.nfc_this_tag_reports_a_different),
                style = MaterialTheme.typography.bodyMedium,
                color = EditorColors.errorAccent,
            )
        }
    }
}
