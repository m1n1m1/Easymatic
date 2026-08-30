package io.github.m1n1m1.easymatic.feature.ai

import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import io.github.m1n1m1.easymatic.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import io.github.m1n1m1.easymatic.domain.model.AiBaseUrl
import io.github.m1n1m1.easymatic.data.ai.OnDeviceStatus
import io.github.m1n1m1.easymatic.domain.model.AiProvider
import io.github.m1n1m1.easymatic.domain.model.isOnDevice
import io.github.m1n1m1.easymatic.domain.model.needsBaseUrl
import io.github.m1n1m1.easymatic.domain.model.needsKey
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorOverlay

/**
 * Adds or edits one AI connection.
 *
 * **The instructions are the feature here, not decoration.** Every other credential
 * in this app is one the user already has — a mail password, a bridge's link
 * button. An API key is a thing most people have never made, minted through a
 * console they have never opened, and "paste your API key" is a sentence that
 * silently excludes everybody who does not already know what one is. So the steps
 * are numbered, and the page opens on a tap: printing a URL somebody has to
 * transcribe into a browser is the same failure a `@Picker` exists to prevent, one
 * layer out. They are **per provider** rather than generic, because a genuinely
 * useful instruction names the button somebody is looking for.
 *
 * **The key field is never populated from storage.** The repository does not hand
 * it back, so editing an existing connection shows an empty box that means "leave
 * the key alone" — stated on the field's own label rather than left to be inferred,
 * because an empty password box that silently keeps the old value is otherwise a
 * fair thing to misread as "this connection has lost its key".
 *
 * **Which fields appear is a plain Compose `when` against the draft**, not
 * `@VisibleWhen`: that annotation drives *node config forms*, derived from a
 * serialization descriptor, and there is no reason for a settings screen to reach
 * for it — `MailAccountEditorOverlay` reads the same way for the same reason.
 */
@Composable
fun AiConnectionEditorOverlay(
    draft: AiConnectionDraft,
    viewModel: AiConnectionsViewModel,
    onClose: () -> Unit,
    onSaved: (String) -> Unit = {},
) {
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val state by viewModel.uiState.collectAsState()
    val macros = state.callableMacros

    EditorOverlay(
        title = if (draft.isNew)
            stringResource(R.string.ai_add_ai_connection) else stringResource(R.string.ai_edit_connection),
        onClose = onClose,
        action = {
            TextButton(
                onClick = { viewModel.save(onSaved) },
                enabled = draft.canSave && !draft.busy,
            ) {
                Text(
                    text = stringResource(R.string.ai_save),
                    color = if (draft.canSave && !draft.busy) {
                        EditorColors.actionAccent
                    } else {
                        EditorColors.textSecondary
                    },
                    fontWeight = FontWeight.Medium,
                )
            }
        },
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // The provider comes first because everything under it depends on the
            // answer — the instructions, whether there is an address to give, and
            // whether the model has to be named.
            ProviderField(draft = draft, onChange = viewModel::onProviderChange)

            OutlinedTextField(
                value = draft.name,
                onValueChange = viewModel::onNameChange,
                label = { Text(stringResource(R.string.ai_name)) },
                placeholder = { Text(stringResource(draft.provider.defaultNameRes())) },
                singleLine = true,
                enabled = !draft.busy,
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            if (draft.isNew || draft.needsKey) {
                SetupInstructions(
                    provider = draft.provider,
                    onOpen = { uriHandler.openUri(draft.provider.consoleUrl()) },
                )
            }

            if (draft.provider.needsBaseUrl) {
                BaseUrlField(
                    draft = draft,
                    onValueChange = viewModel::onBaseUrlChange,
                    onPreset = viewModel::onPresetChosen,
                )
            }

            // One or the other, never both: a provider either authenticates or runs here.
            if (draft.provider.needsKey) {
                KeyField(
                    draft = draft,
                    onKeyChange = viewModel::onKeyChange,
                    onPaste = { clipboard.getText()?.text?.let(viewModel::onKeyChange) },
                )
            } else {
                OnDeviceStatusRow(draft = draft, onDownload = viewModel::download)
            }

            ModelList(
                draft = draft,
                onOpen = viewModel::editModel,
                onAdd = viewModel::addModel,
            )

            ActionButtons(
                draft = draft,
                onTest = viewModel::test,
                onDelete = { viewModel.delete(draft.id) },
            )

            if (draft.isNew) {
                Text(
                    text = stringResource(R.string.ai_save_the_connection_first_then),
                    color = EditorColors.textSecondary,
                    fontSize = 12.sp,
                )
            }

            if (draft.message.isNotBlank()) {
                Surface(
                    color = EditorColors.nodeBackground,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = draft.message,
                        color = if (draft.failed) EditorColors.errorAccent else EditorColors.textPrimary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            Text(
                text = stringResource(
                    R.string.ai_the_key_is_stored_encrypted,
                    stringResource(draft.provider.privacyNoteRes()),
                ),
                color = EditorColors.textSecondary,
                fontSize = 12.sp,
            )
        }
    }

    // A second window over the first, which is what [EditorOverlay] being a Dialog
    // buys — the same nesting the node config form already does for its pin sheet.
    // The profile is edited in place in the draft, so closing this is a step back
    // rather than a save, and nothing reaches storage until the connection does.
    draft.openModel?.let { profile ->
        AiModelEditorOverlay(draft = draft, profile = profile, viewModel = viewModel, macros = macros)
    }
}

/**
 * Which service this connection talks to.
 *
 * A dropdown rather than the `AddHubSheet`-style "what kind?" question the smart
 * home asks first, and the difference is real: pairing a Hue bridge and pairing
 * whatever comes next have nothing in common, so that decision has to be made before
 * a form can be drawn at all. Here every provider is a name, a key and an optional
 * address in the same form — so the choice is a field in it, and changing your mind
 * costs nothing you have already typed.
 *
 * **The field itself is the target**, via `ExposedDropdownMenuBox` and a
 * `PrimaryNotEditable` menu anchor — which is what a read-only `OutlinedTextField`
 * needs to take a tap, and the idiom `ConfigFieldEditor` already uses for every
 * enum in a node's config form. The first cut put a "Change provider" button under
 * the box instead, because a read-only field does not become clickable on its own;
 * that is a second control for one decision, and it reads as a separate action
 * rather than as the field being the thing you touch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderField(draft: AiConnectionDraft, onChange: (AiProvider) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = open,
        onExpandedChange = { if (!draft.busy) open = it },
    ) {
        OutlinedTextField(
            value = stringResource(draft.provider.labelRes()),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.ai_provider)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            singleLine = true,
            enabled = !draft.busy,
            colors = fieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AiProvider.entries.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(stringResource(provider.labelRes())) },
                    onClick = {
                        open = false
                        onChange(provider)
                    },
                )
            }
        }
    }
}

/**
 * Where a self-hosted server lives.
 *
 * **Typed, with presets beside it, and never a chooser** — `@WifiNetwork`'s argument
 * in its purest form: nothing can enumerate the machines on somebody's network, and
 * the server being configured is very often not even switched on yet. The presets
 * are `MailProvider`'s table, carrying the four port numbers people would otherwise
 * have to look up; the host in each is a placeholder, because that part genuinely is
 * unknowable from here.
 *
 * The cleartext note is a **warning and not a refusal**. `http://` to a box in your
 * own house is how these servers ship and there is nothing wrong with it; `http://`
 * across the internet puts an API key on the wire. Only the user knows which of the
 * two they have typed.
 */
@Composable
private fun BaseUrlField(
    draft: AiConnectionDraft,
    onValueChange: (String) -> Unit,
    onPreset: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft.baseUrl,
            onValueChange = onValueChange,
            label = { Text(stringResource(R.string.ai_server_address)) },
            placeholder = { Text("http://192.168.1.10:8000/v1") },
            singleLine = true,
            isError = draft.badBaseUrl,
            enabled = !draft.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SELF_HOSTED_PRESETS.forEach { (name, url) ->
                AssistChip(
                    onClick = { onPreset(url) },
                    enabled = !draft.busy,
                    label = { Text(name, fontSize = 12.sp) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = EditorColors.nodeBackground,
                        labelColor = EditorColors.textPrimary,
                    ),
                )
            }
        }
        when {
            draft.badBaseUrl -> Text(
                text = AiBaseUrl.REQUIREMENT,
                color = EditorColors.errorAccent,
                fontSize = 12.sp,
            )
            draft.cleartext -> Text(
                text = stringResource(R.string.ai_this_address_is_unencrypted_that),
                color = EditorColors.warnAccent,
                fontSize = 12.sp,
            )
        }
    }
}

/**
 * The key box and its paste button.
 *
 * The label carries the whole state of the stored key, because an empty password
 * box is otherwise ambiguous in a way that matters: on an existing connection it
 * means "leave the key alone", and on a restored phone it means "the old one is
 * unreadable and this is not optional".
 */
@Composable
private fun KeyField(
    draft: AiConnectionDraft,
    onKeyChange: (String) -> Unit,
    onPaste: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = draft.key,
            onValueChange = onKeyChange,
            label = {
                Text(
                    when {
                        draft.isNew -> stringResource(R.string.ai_api_key)
                        draft.needsKey -> stringResource(R.string.ai_api_key_paste_it_in)
                        else -> stringResource(R.string.ai_new_api_key_leave_empty)
                    },
                )
            },
            singleLine = true,
            enabled = !draft.busy,
            // Masked: a key is a credential, and this form is most often filled on
            // a phone somebody else can see.
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            colors = fieldColors(),
            modifier = Modifier.weight(1f),
        )
        // The key arrives by clipboard every single time — it is a long string of
        // base64 nobody types — so the paste is worth a button rather than a
        // long-press and a popup on a masked field.
        OutlinedButton(onClick = onPaste, enabled = !draft.busy) {
            Icon(
                imageVector = Icons.Filled.ContentPaste,
                contentDescription = stringResource(R.string.ai_paste_key),
                tint = EditorColors.textPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * What this phone can do about the on-device model, and the one button that changes it.
 *
 * **It stands exactly where the key field does for every other provider**, and that is
 * the shape rather than a coincidence: this is the same question — is this connection
 * able to answer yet? — asked of a phone instead of a console. The four states each get
 * their own sentence for `OnDeviceStatus`' stated reason, and the download button appears
 * only in the one state where pressing it would do something.
 *
 * **An unsupported phone is stated plainly and is not an error**, which is why the
 * sentence names the fallback rather than only the refusal. A connection on a phone that
 * cannot run the model is still a perfectly good connection: every profile under it works
 * through its fallback, and saying so here is the difference between a dead end and a
 * setup step.
 */
@Composable
private fun OnDeviceStatusRow(draft: AiConnectionDraft, onDownload: () -> Unit) {
    val downloading = draft.downloadPercent != null || draft.onDeviceStatus == OnDeviceStatus.DOWNLOADING
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.ai_on_device_heading),
            color = EditorColors.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(statusTextRes(draft.onDeviceStatus)),
            color = EditorColors.textSecondary,
            fontSize = 13.sp,
        )
        if (draft.baseModelName.isNotBlank()) {
            Text(
                text = stringResource(R.string.ai_on_device_base_model, draft.baseModelName),
                color = EditorColors.textSecondary,
                fontSize = 12.sp,
            )
        }
        if (draft.onDeviceStatus == OnDeviceStatus.DOWNLOADABLE && !downloading) {
            OutlinedButton(onClick = onDownload, enabled = !draft.busy) {
                Text(stringResource(R.string.ai_download_model))
            }
        }
        if (downloading) {
            // A device that never said how large the download is gives no percentage, and
            // an indeterminate bar is the honest rendering of that — a confident 0% would
            // read as a download that has stalled.
            val percent = draft.downloadPercent
            if (percent == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Text(
                    text = stringResource(R.string.ai_downloading_percent, percent),
                    color = EditorColors.textSecondary,
                    fontSize = 12.sp,
                )
                LinearProgressIndicator(
                    progress = { percent / PERCENT },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private const val PERCENT = 100f

@StringRes
private fun statusTextRes(status: OnDeviceStatus?): Int = when (status) {
    null -> R.string.ai_on_device_checking
    OnDeviceStatus.AVAILABLE -> R.string.ai_on_device_available
    OnDeviceStatus.DOWNLOADABLE -> R.string.ai_on_device_downloadable
    OnDeviceStatus.DOWNLOADING -> R.string.ai_on_device_downloading
    OnDeviceStatus.UNSUPPORTED -> R.string.ai_on_device_unsupported
}

/**
 * The models this account offers, one row each.
 *
 * **This replaced three fixed rows — Fast, Balanced, Thorough — and the change is the
 * point of the whole restructure.** Those three were the *node's* trade-off bound to
 * products here, which meant everything else about how a model should behave had
 * nowhere to live: the persona sat on the account and applied to all three, and what
 * the model was allowed to do sat on each node separately. A profile is the unit
 * somebody actually has in mind — "my household assistant" — and the tier is now one
 * of its fields rather than its identity.
 *
 * A row names its model rather than its tier, because that is what a node's picker
 * will show. An incomplete one says so on the row: it is the connection's Save that
 * is blocked by it, and a Save button greyed out with nothing saying why is the worst
 * form that failure can take.
 */
@Composable
private fun ModelList(
    draft: AiConnectionDraft,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.ai_models),
            color = EditorColors.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (draft.models.isEmpty()) {
            Text(
                text = stringResource(R.string.ai_no_models_yet),
                color = EditorColors.textSecondary,
                fontSize = 12.sp,
            )
        }
        draft.models.forEach { profile ->
            ModelRow(
                profile = profile,
                provider = draft.provider,
                onOpen = { onOpen(profile.id) },
            )
        }
        TextButton(onClick = onAdd, enabled = !draft.busy) {
            Text(stringResource(R.string.ai_add_model), color = EditorColors.actionAccent)
        }
    }
}

@Composable
private fun ModelRow(
    profile: AiModelProfileDraft,
    provider: AiProvider,
    onOpen: () -> Unit,
) {
    val incomplete = !profile.isComplete(provider)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(EditorColors.nodeBackground)
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.name.ifBlank { stringResource(R.string.ai_unnamed_model) },
                color = EditorColors.textPrimary,
                fontSize = 14.sp,
            )
            Text(
                text = when {
                    incomplete -> stringResource(R.string.ai_model_not_finished)
                    profile.modelId.isNotBlank() -> profile.modelId
                    else -> stringResource(
                        R.string.ai_model_provider_default,
                        stringResource(profile.effort.labelRes()),
                    )
                },
                color = if (incomplete) EditorColors.warnAccent else EditorColors.textSecondary,
                fontSize = 12.sp,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = EditorColors.textSecondary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Test and Delete, both of which need the connection to exist first. */
@Composable
private fun ActionButtons(
    draft: AiConnectionDraft,
    onTest: () -> Unit,
    onDelete: () -> Unit,
) {
    // Enabled only once the connection exists, because the test sends through what
    // is *stored* rather than what is in the box — which is what a macro will use.
    val canTest = !draft.busy && !draft.isNew
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onTest, enabled = canTest) {
            Text(
                text = stringResource(R.string.ai_test),
                color = if (canTest) EditorColors.textPrimary else EditorColors.textSecondary,
            )
        }
        if (!draft.isNew) {
            TextButton(onClick = onDelete, enabled = !draft.busy) {
                Text(stringResource(R.string.ai_delete), color = EditorColors.errorAccent)
            }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = EditorColors.textPrimary,
    unfocusedTextColor = EditorColors.textPrimary,
    focusedBorderColor = EditorColors.actionAccent,
    unfocusedBorderColor = EditorColors.nodeBorder,
    focusedLabelColor = EditorColors.actionAccent,
    unfocusedLabelColor = EditorColors.textSecondary,
    cursorColor = EditorColors.actionAccent,
    focusedContainerColor = EditorColors.canvasBackground.copy(alpha = 0f),
    unfocusedContainerColor = EditorColors.canvasBackground.copy(alpha = 0f),
)
