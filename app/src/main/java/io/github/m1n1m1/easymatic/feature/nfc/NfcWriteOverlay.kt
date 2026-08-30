package io.github.m1n1m1.easymatic.feature.nfc

import androidx.compose.ui.res.stringResource
import io.github.m1n1m1.easymatic.R
import android.nfc.NdefRecord
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.m1n1m1.easymatic.data.nfc.NfcReader
import io.github.m1n1m1.easymatic.domain.model.WebUrl
import io.github.m1n1m1.easymatic.feature.findActivity
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorOverlay

/**
 * Writes a text or a link onto a tag.
 *
 * Worth being clear about what this is and is not. It has **no effect on whether a
 * macro fires**: a trigger matches on the tag's factory-burned id, which nothing
 * can change, so writing is a way to make a tag useful to *other* apps — and to
 * give `trigger.nfc` something to put in `scan.text` — rather than a step in
 * setting a trigger up. Nothing here is required to make a tag work.
 *
 * The Link warning below is the one thing a user must be told before, not after.
 * Android's dispatch order is NDEF, then TECH, then TAG, and Easymatic's filter is
 * the TECH one — so a tag carrying an `https` record is claimed by the browser at
 * the first stage and never reaches us. A Text record is `TNF_WELL_KNOWN`/`RTD_TEXT`
 * and maps to no URI or MIME type, so nothing claims it and it falls through to us.
 * Text is safe; Link is a trade, and it is the user's to make.
 */
@Composable
fun NfcWriteOverlay(
    draft: NfcWriteDraft,
    viewModel: NfcTagsViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    // Only while armed, so the tag can be moved away freely during typing — and so
    // a tag brushed against the phone mid-sentence does not get written to.
    DisposableEffect(activity, draft.armed) {
        if (activity != null && draft.armed) {
            NfcReader.enableReaderMode(activity) { tag ->
                // On the binder thread, with the tag still in the field, which is
                // the only moment the handle is valid.
                viewModel.writeFinished(NfcReader.write(tag, draft.record()))
            }
        }
        onDispose { activity?.let(NfcReader::disableReaderMode) }
    }

    EditorOverlay(title = stringResource(R.string.nfc_write_to_tag), onClose = onClose) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (kind in NfcWriteKind.entries) {
                    FilterChip(
                        selected = draft.kind == kind,
                        onClick = { viewModel.writeKindChanged(kind) },
                        label = {
                            Text(
                                stringResource(
                                    if (kind == NfcWriteKind.TEXT) {
                                        R.string.nfc_kind_text
                                    } else {
                                        R.string.nfc_kind_link
                                    },
                                ),
                            )
                        },
                    )
                }
            }
            OutlinedTextField(
                value = draft.content,
                onValueChange = viewModel::writeContentChanged,
                label = {
                    Text(
                        stringResource(
                            if (draft.kind == NfcWriteKind.TEXT) R.string.nfc_kind_text else R.string.nfc_kind_link,
                        ),
                    )
                },
                placeholder = {
                    Text(if (draft.kind == NfcWriteKind.TEXT) "meeting" else "example.com")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (draft.kind == NfcWriteKind.LINK) {
                Text(
                    text = stringResource(R.string.nfc_a_link_makes_the_tag),
                    style = MaterialTheme.typography.bodyMedium,
                    color = EditorColors.errorAccent,
                )
            }
            if (draft.armed) {
                Text(
                    text = stringResource(R.string.nfc_hold_the_tag_to_the_2),
                    style = MaterialTheme.typography.bodyMedium,
                    color = EditorColors.textSecondary,
                )
                CircularProgressIndicator(color = EditorColors.triggerAccent)
            } else {
                Button(onClick = viewModel::armWrite, enabled = draft.canWrite) { Text(
                    stringResource(R.string.nfc_write)) }
            }
            draft.outcome?.let { outcome ->
                Text(
                    text = outcome,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (draft.failed) EditorColors.errorAccent else EditorColors.textPrimary,
                )
            }
        }
    }
}

/**
 * A link is normalised through [WebUrl] so `example.com` becomes a real URL rather
 * than being written as a relative reference no phone can open — the same reading
 * `action.open_url` gives the field a user types into.
 */
private fun NfcWriteDraft.record(): NdefRecord = when (kind) {
    NfcWriteKind.TEXT -> NdefRecord.createTextRecord(null, content)
    NfcWriteKind.LINK -> NdefRecord.createUri(WebUrl.normalize(content).orEmpty().toUri())
}
