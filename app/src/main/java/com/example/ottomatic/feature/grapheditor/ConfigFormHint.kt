package com.example.ottomatic.feature.grapheditor

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.engine.trigger.GeofenceTrigger

/**
 * A note about a config combination that is valid but does not mean what it
 * looks like, shown under the fields it concerns.
 *
 * Currently one case: a geofence trigger with every transition switched off
 * still fires on enter, because `GeofenceConfig.transitions` falls back to it
 * rather than arming a fence that can never report anything. That fallback is
 * right, but silently disagreeing with three switches the user just turned off
 * is not.
 */
@Composable
internal fun ConfigFormHint(node: WorkflowNode) {
    if (node.typeId != GeofenceTrigger.TYPE_ID) return
    val armed = listOf("onEnter", "onExit", "onDwell").any { key ->
        node.config[ConfigKey(key)]?.toBooleanStrictOrNull() ?: (key == "onEnter")
    }
    if (armed) return
    Text(
        text = "No transition selected \u2014 this trigger falls back to firing on enter.",
        color = EditorColors.triggerAccent,
        fontSize = 12.sp,
    )
}
