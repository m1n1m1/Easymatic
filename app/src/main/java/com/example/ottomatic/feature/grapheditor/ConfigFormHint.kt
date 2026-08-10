package com.example.ottomatic.feature.grapheditor

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.registry.nodeSchema
import com.example.ottomatic.engine.trigger.GeofenceConfig
import com.example.ottomatic.engine.trigger.GeofenceTrigger

/**
 * A note about a config combination that is valid but does not mean what it
 * looks like, shown under the fields it concerns.
 *
 * Currently one case: a geofence trigger with every switch turned off still
 * fires on enter, because `GeofenceConfig.emittedEvents` falls back to it rather
 * than arming a fence that can never report anything. That fallback is right,
 * but silently disagreeing with the switches the user just turned off is not.
 */
@Composable
internal fun ConfigFormHint(node: WorkflowNode) {
    if (node.typeId != GeofenceTrigger.TYPE_ID) return
    // Decoded rather than read key by key. The first version listed the switch
    // names here and repeated their defaults, so adding "on staying away" to the
    // form left a node configured entirely correctly being told it had chosen
    // nothing.
    if (GEOFENCE_SCHEMA.decode(node).hasChosenEvent) return
    Text(
        text = "Nothing selected \u2014 this trigger falls back to firing on enter.",
        color = EditorColors.triggerAccent,
        fontSize = 12.sp,
    )
}

private val GEOFENCE_SCHEMA = nodeSchema<GeofenceConfig>()
