// ConditionActions is the only class here; the file is named for the composable
// it exists to serve, which is what a reader looks for.
@file:Suppress("MatchingDeclarationName")

package com.example.ottomatic.feature.grapheditor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.domain.model.AttachedCondition
import com.example.ottomatic.domain.model.ConditionLogic
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.registry.NodeTypeRegistry
import com.example.ottomatic.domain.registry.effectiveConditionSchema

/** The edits the conditions section can make, forwarded to the ViewModel. */
data class ConditionActions(
    val onAdd: () -> Unit,
    val onRemove: (Int) -> Unit,
    val onConfigChange: (Int, ConfigKey, String) -> Unit,
    val onNegatedChange: (Int, Boolean) -> Unit,
    val onLogicChange: (ConditionLogic) -> Unit,
)

/**
 * The "Conditions" block of the node config sheet: the *attached* placement of a
 * condition node, edited in place rather than wired on the canvas.
 *
 * Each row is a full condition node — the same declaration that can be dropped on
 * the canvas — so its form is rendered by the very same [ConfigFieldEditor] used
 * for node config, over the schema from [effectiveConditionSchema].
 */
@Composable
fun ConditionsSection(
    workflow: Workflow,
    node: WorkflowNode,
    actions: ConditionActions,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Conditions",
            color = EditorColors.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (node.conditions.size > 1) {
            LogicSelector(current = node.conditionLogic, onChange = actions.onLogicChange)
        }
    }
    Text(
        text = if (node.conditions.isEmpty()) {
            "Always runs. Add a condition to run it only when something is true."
        } else {
            "Skips this node and everything below it unless these hold."
        },
        color = EditorColors.textSecondary,
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 2.dp),
    )
    node.conditions.forEachIndexed { index, attached ->
        ConditionRow(
            workflow = workflow,
            node = node,
            attached = attached,
            index = index,
            actions = actions,
        )
    }
    TextButton(onClick = actions.onAdd, modifier = Modifier.padding(top = 4.dp)) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            tint = EditorColors.conditionAccent,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = "Add condition",
            color = EditorColors.conditionAccent,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/** AND/OR toggle, shown only once a second condition makes the choice meaningful. */
@Composable
private fun LogicSelector(current: ConditionLogic, onChange: (ConditionLogic) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ConditionLogic.values().forEach { logic ->
            FilterChip(
                selected = logic == current,
                onClick = { onChange(logic) },
                label = { Text(logic.name, fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = EditorColors.conditionAccent.copy(alpha = 0.22f),
                    selectedLabelColor = EditorColors.conditionAccent,
                    labelColor = EditorColors.textSecondary,
                ),
            )
        }
    }
}

@Composable
private fun ConditionRow(
    workflow: Workflow,
    node: WorkflowNode,
    attached: AttachedCondition,
    index: Int,
    actions: ConditionActions,
) {
    val definition = NodeTypeRegistry.byId(attached.typeId)
    val schema = effectiveConditionSchema(workflow, node, attached)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(EditorColors.nodeBackground)
            .border(1.dp, EditorColors.nodeBorder, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = nodeIcon(definition?.icon ?: com.example.ottomatic.domain.model.NodeIcon.SPLIT),
                contentDescription = null,
                tint = EditorColors.conditionAccent,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = definition?.displayName ?: attached.typeId.value,
                color = EditorColors.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            // "Not" inverts the whole condition, so one declaration covers both
            // "WiFi is on" and "WiFi is not on".
            FilterChip(
                selected = attached.negated,
                onClick = { actions.onNegatedChange(index, !attached.negated) },
                label = { Text("Not", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = EditorColors.conditionAccent.copy(alpha = 0.22f),
                    selectedLabelColor = EditorColors.conditionAccent,
                    labelColor = EditorColors.textSecondary,
                ),
            )
            IconButton(onClick = { actions.onRemove(index) }, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Remove condition",
                    tint = EditorColors.textSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (schema != null) {
            Spacer(modifier = Modifier.height(8.dp))
            schema.fields.forEach { field ->
                ConfigFieldEditor(
                    field = field,
                    value = attached.config[field.key] ?: field.defaultValue,
                    onValueChange = { actions.onConfigChange(index, field.key, it) },
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}
