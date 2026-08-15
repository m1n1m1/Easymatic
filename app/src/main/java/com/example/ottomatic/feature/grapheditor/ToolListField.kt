package com.example.ottomatic.feature.grapheditor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ottomatic.R
import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.NodeTypeDefinition
import com.example.ottomatic.domain.model.ToolSpec
import com.example.ottomatic.domain.model.ToolTarget
import com.example.ottomatic.domain.model.WorkflowSummary
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.domain.registry.ConfigSchemaRegistry
import com.example.ottomatic.domain.registry.NodeTypeRegistry
import com.example.ottomatic.domain.registry.PluginNodes
import com.example.ottomatic.engine.ai.canRunAsTool
import com.example.ottomatic.feature.i18n.rememberNodeText
import com.example.ottomatic.feature.workflowlist.LocalMacros
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A `@Tools` field: what an AI node is allowed to do.
 *
 * **Two levels, because a tool is two decisions.** The list says *which* node or macro;
 * tapping a row says *how much of it is fixed*. Splitting them is what keeps the first
 * screen a short list of recognisable names rather than a wall of forms.
 *
 * **The chooser is not written here.** Picking a node is [NodePaletteOverlay] with its
 * `restrictedTo` set — the parameter it already had for the port-drag case. That is
 * worth more than the code it saves: the palette brings search, the kind cards, the
 * collapsible groups, plugin grouping and translated node text, and a hand-rolled list
 * beside it would drift from all of them the first time any one changed.
 *
 * **A macro cannot be added here**, and that is a decision about this form rather than
 * about the feature: [ToolTarget.Macro] is still a tool the engine runs, so a spec
 * that names one keeps working and renders a row like any other. There is simply no
 * button that creates one.
 *
 * Fully controlled, on [PortListField]'s rule and for its reason: there is no local
 * editing state, so the persisted text and what the form shows can never disagree.
 */
@Composable
internal fun ToolListField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
) {
    val rows = remember(value) { ToolSpec.parse(value) }
    var addingNode by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = label, color = EditorColors.textPrimary, fontSize = 14.sp)
        if (rows.isEmpty()) {
            Text(
                text = stringResource(R.string.config_tools_none),
                color = EditorColors.textSecondary,
                fontSize = 12.sp,
            )
        }
        rows.forEachIndexed { index, row ->
            ToolRow(
                spec = row,
                onOpen = { editing = index },
                onRemove = { onValueChange(ToolSpec.encode(rows.filterIndexed { i, _ -> i != index })) },
            )
        }
        if (rows.size < ToolSpec.MAX_TOOLS) {
            TextButton(onClick = { addingNode = true }) {
                Text(stringResource(R.string.config_tools_add))
            }
        }
    }

    if (addingNode) {
        ToolNodePicker(
            onPick = { target ->
                addingNode = false
                onValueChange(ToolSpec.encode(rows + ToolSpec(target)))
            },
            onDismiss = { addingNode = false },
        )
    }

    editing?.let { index ->
        rows.getOrNull(index)?.let { spec ->
            ToolPinOverlay(
                spec = spec,
                onChange = { updated ->
                    onValueChange(ToolSpec.encode(rows.toMutableList().also { it[index] = updated }))
                },
                onDismiss = { editing = null },
            )
        }
    }
}

/**
 * The node palette, restricted to what can actually run as a tool.
 *
 * `canWiden = false` is the whole difference from the port-drag case: there the
 * restriction is a suggestion and "Show all" is the escape hatch, here it is a rule —
 * a trigger or a loop offered as a tool would silently do nothing.
 */
@Composable
private fun ToolNodePicker(onPick: (ToolTarget) -> Unit, onDismiss: () -> Unit) {
    // Keyed on the plugin set for the palette's own reason: `NodeTypeRegistry.all` is
    // the compiled registries plus whichever plugins are enabled right now, and
    // Compose cannot observe a plain function call.
    val plugins by PluginNodes.entries.collectAsState()
    val runnable = remember(plugins) {
        NodeTypeRegistry.all.filter { canRunAsTool(it.typeId, it.kind) }.map { it.typeId }.toSet()
    }
    NodePaletteOverlay(
        title = stringResource(R.string.config_tools_choose),
        restrictedTo = runnable,
        canWiden = false,
        onPick = { definition -> onPick(ToolTarget.Node(definition.typeId)) },
        onDismiss = onDismiss,
    )
}

/** One tool in the list: what it is, and how much of it the author already decided. */
@Composable
private fun ToolRow(spec: ToolSpec, onOpen: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(EditorColors.nodeBackground)
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = toolTitle(spec.target), color = EditorColors.textPrimary, fontSize = 14.sp)
            Text(
                text = stringResource(R.string.config_tools_fixed_count, spec.pinned.size),
                color = EditorColors.textSecondary,
                fontSize = 12.sp,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.config_tools_remove),
                tint = EditorColors.textSecondary,
            )
        }
    }
}

/**
 * Decides, field by field, what the author fixes and what the model fills in.
 *
 * **A picker field has no switch**: an identifier is chosen here or the tool is
 * useless, because a model cannot invent a hub reference or a UUID and a wrong one
 * names nothing rather than failing. That asymmetry is the whole safety argument for
 * the feature, so the form states it rather than leaving it to be discovered.
 */
@Composable
private fun ToolPinOverlay(
    spec: ToolSpec,
    onChange: (ToolSpec) -> Unit,
    onDismiss: () -> Unit,
) {
    val fields = remember(spec.target) {
        (spec.target as? ToolTarget.Node)?.let { ConfigSchemaRegistry.byId(it.typeId)?.fields }.orEmpty()
    }
    EditorOverlay(title = toolTitle(spec.target), onClose = onDismiss) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (fields.isEmpty()) {
                Text(
                    text = stringResource(R.string.config_tools_nothing_to_fix),
                    color = EditorColors.textSecondary,
                    fontSize = 12.sp,
                )
            }
            fields.forEach { field ->
                val pinned = field.key in spec.pinned
                val mustPin = field.type is ConfigFieldType.PICKER
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(
                                if (mustPin) R.string.config_tools_must_fix else R.string.config_tools_fixed,
                            ),
                            color = EditorColors.textSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (!mustPin) {
                            Switch(
                                checked = pinned,
                                onCheckedChange = { on ->
                                    onChange(
                                        spec.copy(
                                            pinned = if (on) {
                                                spec.pinned + (field.key to field.defaultValue)
                                            } else {
                                                spec.pinned - field.key
                                            },
                                        ),
                                    )
                                },
                            )
                        }
                    }
                    if (pinned || mustPin) {
                        ConfigFieldEditor(
                            field = field,
                            value = spec.pinned[field.key].orEmpty(),
                            onValueChange = { updated ->
                                onChange(spec.copy(pinned = spec.pinned + (field.key to updated)))
                            },
                            siblingValue = { key -> spec.pinned[key].orEmpty() },
                        )
                    }
                }
            }
        }
    }
}

/** What a row is called: the node's own display name, or the macro's. */
@Composable
private fun toolTitle(target: ToolTarget): String = when (target) {
    is ToolTarget.Node -> nodeTitle(target.typeId)
    is ToolTarget.Macro -> macroTitle(target.macroId)
}

@Composable
private fun nodeTitle(typeId: NodeTypeId): String {
    val definition: NodeTypeDefinition? = remember(typeId) { NodeTypeRegistry.byId(typeId) }
    val text = rememberNodeText()
    return definition?.let { text.name(it) } ?: stringResource(R.string.config_tools_missing)
}

/**
 * A macro tool's row is named after the macro, resolved through the same library the
 * `@Picker(MACRO)` field uses — so a renamed macro renames its tool with it, and a
 * deleted one says so here as well as in the Problems panel.
 */
@Composable
private fun macroTitle(macroId: String): String {
    val library = LocalMacros.current
    val empty = remember { MutableStateFlow(emptyList<WorkflowSummary>()) }
    val macros by (library?.macros ?: empty).collectAsState()
    return macros.firstOrNull { it.id == macroId }?.name
        ?: stringResource(R.string.config_tools_missing)
}

/** Kotlin cannot infer this from the map alone; named so the call sites read. */
private operator fun Map<ConfigKey, String>.minus(key: ConfigKey): Map<ConfigKey, String> =
    filterKeys { it != key }
