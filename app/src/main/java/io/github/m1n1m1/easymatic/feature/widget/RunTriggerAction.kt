package io.github.m1n1m1.easymatic.feature.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import io.github.m1n1m1.easymatic.core.service.RunFeedback
import io.github.m1n1m1.easymatic.engine.service.MacroEngineService

/**
 * What a tap on a tile does.
 *
 * Two steps, and the order matters. [RunFeedback.running] is set **here**, in the
 * tap, rather than left to the service — a `startForegroundService` has to be
 * scheduled, the service has to be created, and the graph has to be loaded from
 * disk before the engine has any opinion at all, which is several hundred
 * milliseconds of a button that looks like it was not pressed. Setting it here
 * makes the tile respond to the tap rather than to the run.
 *
 * The service overwrites the same entry a moment later with its own RUNNING, then
 * with the outcome. That is a redundant write, not a conflicting one: both say the
 * same thing about the same key, and the second carries the macro's real name
 * where this one only has what the widget had cached.
 */
class RunTriggerAction : ActionCallback {

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val workflowId = parameters[WORKFLOW_ID_KEY] ?: return
        val nodeId = parameters[NODE_ID_KEY] ?: return
        val label = parameters[LABEL_KEY].orEmpty()
        val macroName = parameters[MACRO_NAME_KEY].orEmpty()

        RunFeedback.running(
            RunFeedback.Target(workflowId, nodeId, macroName, label),
            System.currentTimeMillis(),
        )
        // Redraw immediately so the tap lands visibly. WidgetUpdater will redraw
        // again when the run reports back; this one exists only to close the gap
        // between the finger and the engine.
        updateAllWidgets(context)
        MacroEngineService.runManual(context, workflowId, nodeId)
    }

    companion object {
        val WORKFLOW_ID_KEY = ActionParameters.Key<String>("workflowId")
        val NODE_ID_KEY = ActionParameters.Key<String>("nodeId")
        val LABEL_KEY = ActionParameters.Key<String>("label")
        val MACRO_NAME_KEY = ActionParameters.Key<String>("macroName")
    }
}
