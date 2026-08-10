package com.example.ottomatic.feature.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.example.ottomatic.feature.grapheditor.EditorColors
import com.example.ottomatic.feature.grapheditor.EditorOverlay

/** Where a Gemini key is minted. Opened directly rather than printed to be typed. */
private const val AI_STUDIO_URL = "https://aistudio.google.com/apikey"

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
 * layer out.
 *
 * **The key field is never populated from storage.** The repository does not hand
 * it back, so editing an existing connection shows an empty box that means "leave
 * the key alone" — stated on the field's own label rather than left to be inferred,
 * because an empty password box that silently keeps the old value is otherwise a
 * fair thing to misread as "this connection has lost its key".
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
            OutlinedTextField(
                value = draft.name,
                onValueChange = viewModel::onNameChange,
                label = { Text("Name") },
                placeholder = { Text("Gemini") },
                singleLine = true,
                enabled = !draft.busy,
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            // Only one provider today. The row is shown anyway so the field a second
            // one will appear in is already where people expect it — and so "which
            // service is this?" is answered on the card rather than assumed.
            Text(
                text = "Provider: Google Gemini",
                color = EditorColors.textSecondary,
                fontSize = 13.sp,
            )

            if (draft.isNew || draft.needsKey) {
                SetupInstructions(onOpen = { uriHandler.openUri(AI_STUDIO_URL) })
            }

            KeyField(
                draft = draft,
                onKeyChange = viewModel::onKeyChange,
                onPaste = { clipboard.getText()?.text?.let(viewModel::onKeyChange) },
            )

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
                    "Prompts are answered by Google's servers, so an Ask AI node needs a " +
                    "connection and takes a moment. Nothing is sent unless a macro reaches one.",
                color = EditorColors.textSecondary,
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

/**
 * How to get a key, in the order it is actually done.
 *
 * Written as steps rather than a paragraph because it is a procedure in another
 * app: somebody following it is switching back and forth and needs to find their
 * place again, which prose does not let them do.
 */
@Composable
private fun SetupInstructions(onOpen: () -> Unit) {
    Surface(
        color = EditorColors.nodeBackground,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Getting a key — it is free",
                color = EditorColors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Step(1, "Tap the button below. Google AI Studio opens in your browser.")
            Step(2, "Sign in with your Google account if you are asked to.")
            Step(3, "Tap \"Create API key\". If it asks which project to use, pick any — " +
                "or let it make a new one for you.")
            Step(4, "Tap the key to copy it.")
            Step(5, "Come back here and tap the paste button beside the key field.")

            Button(
                onClick = onOpen,
                colors = ButtonDefaults.buttonColors(
                    containerColor = EditorColors.actionAccent,
                    contentColor = EditorColors.textPrimary,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(text = "Open Google AI Studio", modifier = Modifier.padding(start = 8.dp))
            }

            Text(
                text = "The free tier is generous, but it is your quota — a macro that asks the " +
                    "AI every minute will use it up. You can revoke the key from the same page " +
                    "at any time.",
                color = EditorColors.textSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            color = EditorColors.actionAccent.copy(alpha = 0.18f),
            shape = CircleShape,
            modifier = Modifier.size(22.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "$number",
                    color = EditorColors.actionAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Text(
            text = text,
            color = EditorColors.textSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
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
