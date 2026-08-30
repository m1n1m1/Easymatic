package io.github.m1n1m1.easymatic.feature.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.m1n1m1.easymatic.R
import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.domain.model.NodeTypeDefinition
import io.github.m1n1m1.easymatic.domain.model.ToolSpec
import io.github.m1n1m1.easymatic.domain.model.ToolTarget
import io.github.m1n1m1.easymatic.domain.model.WorkflowSummary
import io.github.m1n1m1.easymatic.domain.registry.ConfigFieldType
import io.github.m1n1m1.easymatic.domain.registry.ConfigSchemaRegistry
import io.github.m1n1m1.easymatic.domain.registry.NodeTypeRegistry
import io.github.m1n1m1.easymatic.domain.registry.PickerOptions
import io.github.m1n1m1.easymatic.feature.grapheditor.ConfigFieldEditor
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorOverlay
import io.github.m1n1m1.easymatic.feature.i18n.rememberNodeText
import io.github.m1n1m1.easymatic.feature.workflowlist.LocalMacros
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Decides, field by field, what the author fixes and what the model fills in.
 *
 * **A picker gets a switch exactly when its answer set can be enumerated**, and that
 * is the distinction the whole feature turns on. The rule used to be *every* picker
 * must be pinned, because a model cannot invent a hub reference or a UUID and a wrong
 * one names nothing rather than failing. That reasoning is sound and the conclusion was
 * too broad: where the set is knowable the model is handed **the set**, so it chooses a
 * real scene and can produce nothing else. The author is no longer forced to decide in
 * advance which one.
 *
 * Where it is *not* knowable — a sound URI, an app package, a mail account — nothing
 * changes: no switch, and the row stays badged until it is answered.
 *
 * It moved here from the graph editor with the tool list itself: what a tool is
 * allowed to do is a property of the model profile it is asked through, so its form
 * belongs beside the profile rather than beside a node — and the node's adjustments
 * open this same form.
 */
@Composable
internal fun ToolPinOverlay(
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
                val picker = field.type as? ConfigFieldType.PICKER
                val choices = picker?.let { PickerOptions.of(it.kind, scopeOf(it, spec)) }.orEmpty()
                // A picker with nothing to offer is the only field left that has to be
                // answered here; everything else, pickers included, may be left open.
                val mustPin = picker != null && choices.isEmpty()
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
                    } else if (choices.isNotEmpty()) {
                        // Said explicitly because an open field and an unanswered one
                        // look identical, and only one of them is a mistake.
                        Text(
                            text = stringResource(
                                R.string.config_tools_ai_chooses,
                                choices.size,
                                field.label,
                            ),
                            color = EditorColors.textSecondary,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A scoped picker's scope, read from what this tool already pins.
 *
 * The same resolution `NodeToolCatalog` performs on the run path, and it has to be:
 * the form promising a choice the catalogue then withholds is the one way these two
 * could disagree, and it would show as a switch that does nothing.
 */
private fun scopeOf(type: ConfigFieldType.PICKER, spec: ToolSpec): List<String> =
    type.scopedBy.map { spec.pinned[ConfigKey(it)].orEmpty() }

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
