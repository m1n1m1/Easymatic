package com.example.ottomatic.feature.ai

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.sp
import com.example.ottomatic.core.service.AiModel
import com.example.ottomatic.domain.model.AiBaseUrl
import com.example.ottomatic.domain.model.AiProvider
import com.example.ottomatic.domain.model.needsBaseUrl
import com.example.ottomatic.domain.model.needsModelIds
import com.example.ottomatic.feature.grapheditor.EditorColors
import com.example.ottomatic.feature.grapheditor.EditorOverlay

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

    EditorOverlay(
        title = if (draft.isNew) "Add AI connection" else "Edit connection",
        onClose = onClose,
        action = {
            TextButton(
                onClick = { viewModel.save(onSaved) },
                enabled = draft.canSave && !draft.busy,
            ) {
                Text(
                    text = "Save",
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
                label = { Text("Name") },
                placeholder = { Text(draft.provider.defaultName()) },
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

            KeyField(
                draft = draft,
                onKeyChange = viewModel::onKeyChange,
                onPaste = { clipboard.getText()?.text?.let(viewModel::onKeyChange) },
            )

            ModelFields(
                draft = draft,
                onModelChange = viewModel::onModelChange,
                onLoadModels = viewModel::loadModels,
                onChooserDismiss = viewModel::closeModelChooser,
            )

            SystemPromptField(draft = draft, onValueChange = viewModel::onSystemPromptChange)

            ActionButtons(
                draft = draft,
                onTest = viewModel::test,
                onDelete = { viewModel.delete(draft.id) },
            )

            if (draft.isNew) {
                Text(
                    text = "Save the connection first, then Test to check the key works.",
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
                text = "The key is stored encrypted on this phone and is never shown again. " +
                    draft.provider.privacyNote() +
                    " Nothing is sent unless a macro reaches an Ask AI node.",
                color = EditorColors.textSecondary,
                fontSize = 12.sp,
            )
        }
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
            value = draft.provider.label(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Provider") },
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
                    text = { Text(provider.label()) },
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
            label = { Text("Server address") },
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
                text = "This address is unencrypted. That is normal for a server on your own " +
                    "network — do not use it for anything across the internet, because the " +
                    "key would be sent in the clear.",
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
                        draft.isNew -> "API key"
                        draft.needsKey -> "API key — paste it in again"
                        else -> "New API key (leave empty to keep the current one)"
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
                contentDescription = "Paste key",
                tint = EditorColors.textPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Which model each of the node's three speed settings uses.
 *
 * **Three rows rather than one**, because `AiModel` is a trade-off the *macro*
 * chooses and this is where that trade-off is bound to products. Leaving a row blank
 * is meaningful and different per provider: where the provider publishes a table it
 * means "use the default", and where it does not it means "use whatever the Fast row
 * names", which is exactly right for a machine serving one model.
 *
 * The list button is the [com.example.ottomatic.data.ai.AiModelCatalog] chooser, and
 * it needs the connection saved for the reason Test does — it reads through what is
 * *stored*, which is what a macro will use.
 */
@Composable
private fun ModelFields(
    draft: AiConnectionDraft,
    onModelChange: (AiModel, String) -> Unit,
    onLoadModels: (AiModel) -> Unit,
    onChooserDismiss: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Models",
            color = EditorColors.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        AiModel.entries.forEach { model ->
            ModelRow(
                draft = draft,
                model = model,
                onValueChange = { onModelChange(model, it) },
                onLoad = { onLoadModels(model) },
                onPick = {
                    onModelChange(model, it)
                    onChooserDismiss()
                },
                onDismiss = onChooserDismiss,
            )
        }
        Text(
            text = if (draft.provider.needsModelIds) {
                "Name the model your server or account serves. If you only fill in Fast, " +
                    "the other two use it as well."
            } else {
                "Leave these empty to use the provider's own models. Fill one in when a " +
                    "model is retired, or to pin a particular one."
            },
            color = EditorColors.textSecondary,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun ModelRow(
    draft: AiConnectionDraft,
    model: AiModel,
    onValueChange: (String) -> Unit,
    onLoad: () -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val required = draft.provider.needsModelIds && model == AiModel.FAST
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = draft.modelFor(model),
            onValueChange = onValueChange,
            label = { Text(model.label()) },
            placeholder = { Text(if (required) "required" else "provider's default") },
            singleLine = true,
            isError = required && draft.modelFor(model).isBlank(),
            enabled = !draft.busy,
            colors = fieldColors(),
            modifier = Modifier.weight(1f),
        )
        Column {
            OutlinedButton(onClick = onLoad, enabled = !draft.busy && !draft.isNew) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.FormatListBulleted,
                    contentDescription = "List models",
                    tint = if (draft.isNew) EditorColors.textSecondary else EditorColors.textPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(
                expanded = draft.choosingFor == model,
                onDismissRequest = onDismiss,
            ) {
                draft.models.forEach { id ->
                    DropdownMenuItem(text = { Text(id) }, onClick = { onPick(id) })
                }
            }
        }
    }
}

/**
 * A standing instruction for every macro that uses this connection.
 *
 * Its helper text spells out that it is *added to* rather than replaced by the node's
 * own field, because that is the one thing about it somebody could reasonably guess
 * wrong — and guessing wrong means a persona quietly dropped by every node that sets
 * a task instruction.
 */
@Composable
private fun SystemPromptField(draft: AiConnectionDraft, onValueChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = draft.systemPrompt,
            onValueChange = onValueChange,
            label = { Text("Standing instruction (optional)") },
            placeholder = { Text("Answer briefly and in plain language.") },
            minLines = 2,
            enabled = !draft.busy,
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "Sent ahead of every prompt through this connection. An Ask AI node's own " +
                "standing instruction is added after it rather than replacing it.",
            color = EditorColors.textSecondary,
            fontSize = 12.sp,
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
                text = "Test",
                color = if (canTest) EditorColors.textPrimary else EditorColors.textSecondary,
            )
        }
        if (!draft.isNew) {
            TextButton(onClick = onDelete, enabled = !draft.busy) {
                Text("Delete", color = EditorColors.errorAccent)
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
