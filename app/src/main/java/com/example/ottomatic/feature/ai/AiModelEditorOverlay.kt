package com.example.ottomatic.feature.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ottomatic.R
import com.example.ottomatic.core.service.AiModel
import com.example.ottomatic.core.service.CallableMacro
import com.example.ottomatic.domain.model.AiModelProfile
import com.example.ottomatic.domain.model.ToolSpec
import com.example.ottomatic.domain.model.isOnDevice
import com.example.ottomatic.domain.model.needsModelIds
import com.example.ottomatic.feature.grapheditor.EditorColors
import com.example.ottomatic.feature.grapheditor.EditorOverlay

/**
 * One model profile: which model, how it should behave, and what it may do.
 *
 * **A second window over the connection's own**, and edited in place rather than
 * through a nested draft with its own Save. A profile has no existence apart from the
 * account that answers it, so it commits when the connection does; closing this is a
 * step back rather than a discard, and the connection's Save is what refuses an
 * incomplete row.
 *
 * The three fields the request named are here in the order somebody fills them —
 * which model, what it should be like, what it is allowed to do — with the tier
 * beside the model id because the two answer the same question at different
 * resolutions: naming an id is exact, and leaving it blank means "whatever this
 * provider calls its Fast one", which is the ordinary case for a provider that
 * publishes a table.
 */
@Composable
internal fun AiModelEditorOverlay(
    draft: AiConnectionDraft,
    profile: AiModelProfileDraft,
    viewModel: AiConnectionsViewModel,
    macros: List<CallableMacro> = emptyList(),
) {
    var editingTools by remember { mutableStateOf(false) }
    EditorOverlay(
        title = profile.name.ifBlank { stringResource(R.string.ai_unnamed_model) },
        onClose = viewModel::closeModelEditor,
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
                value = profile.name,
                onValueChange = { value -> viewModel.updateModel(profile.id) { it.copy(name = value) } },
                label = { Text(stringResource(R.string.ai_model_name)) },
                placeholder = { Text(stringResource(R.string.ai_model_name_placeholder)) },
                singleLine = true,
                isError = profile.name.isBlank(),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.ai_model_name_help),
                color = EditorColors.textSecondary,
                fontSize = 12.sp,
            )

            // One or the other. An on-device profile has no model to name — the phone
            // has exactly one and says so on the connection above — and it is the only
            // kind that needs somewhere else to send a question it cannot answer.
            if (draft.provider.isOnDevice) {
                FallbackField(draft = draft, profile = profile, viewModel = viewModel)
            } else {
                ModelIdField(draft = draft, profile = profile, viewModel = viewModel)
            }

            EffortField(profile = profile, viewModel = viewModel)

            ReplyLimitField(profile = profile, viewModel = viewModel)

            SystemPromptField(profile = profile, viewModel = viewModel)

            ToolsRow(profile = profile, onOpen = { editingTools = true })

            TextButton(
                onClick = { viewModel.deleteModel(profile.id) },
                enabled = !draft.busy,
            ) {
                Text(stringResource(R.string.ai_delete_model), color = EditorColors.errorAccent)
            }
        }
    }

    if (editingTools) {
        ToolPermissionsOverlay(
            tools = profile.tools,
            macros = macros,
            onChange = { tools -> viewModel.updateModel(profile.id) { it.copy(tools = tools) } },
            onClose = { editingTools = false },
        )
    }
}

/**
 * How many things this model may do, and the way in to changing it.
 *
 * A row rather than the list itself, because the list is every runnable node in the
 * app: putting seventy checkboxes inside the form that also holds a name and a prompt
 * would bury the three fields somebody came here for.
 */
@Composable
private fun ToolsRow(profile: AiModelProfileDraft, onOpen: () -> Unit) {
    val allowed = remember(profile.tools) { ToolSpec.parse(profile.tools).size }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(EditorColors.nodeBackground)
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.ai_tools_title),
                color = EditorColors.textPrimary,
                fontSize = 14.sp,
            )
            Text(
                text = if (allowed == 0) {
                    stringResource(R.string.ai_tools_none_allowed)
                } else {
                    stringResource(R.string.ai_tools_n_allowed, allowed)
                },
                color = EditorColors.textSecondary,
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

/**
 * Which model this profile asks for.
 *
 * **Editable with a chooser beside it**, unchanged from the field it replaces and for
 * the same two reasons: a listing only offers what this key can reach *right now*,
 * and the server being configured is frequently switched off or serves no listing at
 * all. The list is a suggestion; the answer set is every model that server accepts.
 */
@Composable
private fun ModelIdField(
    draft: AiConnectionDraft,
    profile: AiModelProfileDraft,
    viewModel: AiConnectionsViewModel,
) {
    val required = draft.provider.needsModelIds
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = profile.modelId,
                onValueChange = { value -> viewModel.updateModel(profile.id) { it.copy(modelId = value) } },
                label = { Text(stringResource(R.string.ai_model_id)) },
                placeholder = {
                    Text(
                        if (required) {
                            stringResource(R.string.ai_model_id_required)
                        } else {
                            stringResource(R.string.ai_provider_s_default)
                        },
                    )
                },
                singleLine = true,
                isError = required && profile.modelId.isBlank(),
                enabled = !draft.busy,
                colors = fieldColors(),
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = viewModel::loadModels, enabled = !draft.busy && !draft.isNew) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.FormatListBulleted,
                    contentDescription = stringResource(R.string.ai_list_models),
                    tint = if (draft.isNew) EditorColors.textSecondary else EditorColors.textPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (draft.choosingModelId) {
            AiModelIdChooserOverlay(
                models = draft.listedModels,
                filter = draft.modalityFilter,
                onToggleModality = viewModel::toggleModality,
                onPick = { id -> viewModel.updateModel(profile.id) { it.copy(modelId = id) } },
                onDismiss = viewModel::closeModelChooser,
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
    }
}

/**
 * Where this profile's questions go when the phone cannot answer them.
 *
 * **A picker rather than a typed id**, on the app's standing rule: this is an opaque
 * profile id, and a mistyped one does not fail loudly — it names nothing, and the node
 * merely looks broken. The answer set is knowable and complete, which is the other half of
 * the test a read-only picker has to pass: every profile in the library is right here.
 *
 * **On-device profiles are not offered**, which is the editor enforcing what `RoutingAi`
 * refuses at run time: a fallback that is itself on-device would fail for exactly the same
 * reason the first one did, and following it would turn one hop into a chain. Offering it
 * and then refusing it would be a form promising a choice the runtime withholds.
 *
 * Blank is a real answer and is offered as a row rather than left as an empty field:
 * somebody with a supported phone who wants nothing to leave it should be able to say so,
 * and get the refusal sentence in the run log instead of a silent trip to the network.
 */
@Composable
private fun FallbackField(
    draft: AiConnectionDraft,
    profile: AiModelProfileDraft,
    viewModel: AiConnectionsViewModel,
) {
    var choosing by remember { mutableStateOf(false) }
    val chosen = profile.fallbackModelRef
        .takeIf { it.isNotBlank() }
        ?.let { viewModel.modelProfile(it)?.name }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = chosen ?: stringResource(R.string.ai_fallback_none),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.ai_fallback_model)) },
            enabled = !draft.busy,
            colors = fieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { choosing = true },
        )
        Text(
            text = stringResource(R.string.ai_fallback_help),
            color = EditorColors.textSecondary,
            fontSize = 12.sp,
        )
    }
    if (choosing) {
        FallbackPickerOverlay(
            viewModel = viewModel,
            selectedId = profile.fallbackModelRef,
            onPick = { id ->
                viewModel.updateModel(profile.id) { it.copy(fallbackModelRef = id) }
                choosing = false
            },
            onDismiss = { choosing = false },
        )
    }
}

/**
 * The wire-backed profiles, flat, with a row for "nowhere".
 *
 * **Its own overlay rather than [AiModelPickerOverlay]**, which would have been the
 * obvious reuse and is the wrong one: that chooser stacks a whole connection editor
 * beneath itself so a node's picker can add an account on the spot, and this one is
 * already *inside* that editor — reusing it would render a second connection editor over
 * the first, on the draft that opened it. A flat list with no "add connection" row is also
 * the right shape here for a second reason: a fallback is chosen from what already exists,
 * and adding an account mid-edit would abandon the draft holding this very field.
 */
@Composable
private fun FallbackPickerOverlay(
    viewModel: AiConnectionsViewModel,
    selectedId: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val offered = state.connections.filterNot { it.provider.isOnDevice }
    EditorOverlay(title = stringResource(R.string.ai_choose_a_model), onClose = onDismiss) { _ ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FallbackRow(
                label = stringResource(R.string.ai_fallback_none),
                selected = selectedId.isBlank(),
                onClick = { onPick("") },
            )
            offered.forEach { connection ->
                Text(
                    text = connection.name,
                    color = EditorColors.textSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
                connection.models.forEach { candidate ->
                    FallbackRow(
                        label = candidate.name,
                        selected = candidate.id == selectedId,
                        onClick = { onPick(candidate.id) },
                    )
                }
            }
            if (offered.isEmpty()) {
                Text(
                    text = stringResource(R.string.ai_no_connections_yet_add_one),
                    color = EditorColors.textSecondary,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun FallbackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) EditorColors.actionAccent else EditorColors.nodeBackground,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            color = EditorColors.textPrimary,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

/**
 * How hard this profile should think.
 *
 * **This is where `AiModel` went**, and the move is what makes the whole restructure
 * safe: a workflow used to persist this enum's name and now persists a profile id the
 * user minted, so the tier can be re-decided here without touching a single macro.
 * It still means something on the wire — Gemini's thinking level, OpenAI's reasoning
 * effort, and whether Anthropic's fast tier is sent a thinking field at all — and it
 * additionally picks the published id when [AiModelProfileDraft.modelId] is blank.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EffortField(profile: AiModelProfileDraft, viewModel: AiConnectionsViewModel) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
            OutlinedTextField(
                value = stringResource(profile.effort.labelRes()),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.ai_model_effort)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                singleLine = true,
                colors = fieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                AiModel.entries.forEach { effort ->
                    DropdownMenuItem(
                        text = { Text(stringResource(effort.labelRes())) },
                        onClick = {
                            open = false
                            viewModel.updateModel(profile.id) { it.copy(effort = effort) }
                        },
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.ai_model_effort_help),
            color = EditorColors.textSecondary,
            fontSize = 12.sp,
        )
    }
}

/**
 * A standing instruction for every macro that asks through this profile.
 *
 * Its helper text spells out that it is *added to* rather than replaced by the node's
 * own field, because that is the one thing about it somebody could reasonably guess
 * wrong — and guessing wrong means a persona quietly dropped by every node that sets
 * a task instruction.
 */
@Composable
private fun SystemPromptField(profile: AiModelProfileDraft, viewModel: AiConnectionsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = profile.systemPrompt,
            onValueChange = { value -> viewModel.updateModel(profile.id) { it.copy(systemPrompt = value) } },
            label = { Text(stringResource(R.string.ai_standing_instruction_optional)) },
            placeholder = { Text(stringResource(R.string.ai_answer_briefly_and_in_plain)) },
            minLines = 2,
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.ai_sent_ahead_of_every_prompt),
            color = EditorColors.textSecondary,
            fontSize = 12.sp,
        )
    }
}

/**
 * How long a reply may be, for whatever asks through this profile without saying.
 *
 * Its helper text spells out that a node's own "Longest reply" field wins, because that
 * is the one thing about it somebody could reasonably guess wrong — and guessing wrong
 * here means believing every macro on this key was just given eight times the room.
 *
 * Digits only, and the field is text rather than a number so that clearing it to retype
 * is not read as asking for zero. What a blank finally saves is the built-in default.
 */
@Composable
private fun ReplyLimitField(profile: AiModelProfileDraft, viewModel: AiConnectionsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = profile.maxOutputTokens,
            onValueChange = { value ->
                viewModel.updateModel(profile.id) { it.copy(maxOutputTokens = value.filter(Char::isDigit)) }
            },
            label = { Text(stringResource(R.string.ai_reply_limit)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.ai_reply_limit_help),
            color = EditorColors.textSecondary,
            fontSize = 12.sp,
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
