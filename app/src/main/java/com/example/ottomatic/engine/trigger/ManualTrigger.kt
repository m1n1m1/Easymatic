package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.pulseTriggerNode
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.Serializable

/**
 * Config for `trigger.manual`.
 *
 * The label is what a home-screen tile, a deck cell and a pinned shortcut call
 * this button. A macro may hold several manual triggers — "Start", "Stop",
 * "Reset" — and off the canvas there is no graph to tell them apart by position,
 * so without a name of their own a deck would show the same macro three times.
 *
 * Blank rather than "Run" as the default, because blank is what lets the fallback
 * chain in `ManualTriggerRef` work: an unnamed button takes the node's name, and
 * failing that the macro's, which is right far more often than a grid of buttons
 * all labelled "Run".
 */
@Serializable
data class ManualTriggerConfig(
    @Label("Button label") val label: String = "",
)

/**
 * Trigger for `trigger.manual`. Fires when the user taps "Run" in the editor.
 *
 * Holds a [MutableSharedFlow] per activation so the ViewModel can emit into it.
 * The companion keeps a registry of active flows keyed by node id so the
 * UI can reach the correct trigger instance.
 *
 * This is **not** how a widget or a shortcut runs a macro. Those go through
 * `runManualTrigger`, which executes from the graph directly, because this
 * registry cannot serve them: it is keyed by node id alone, so the editor's
 * preview runner and the engine's armed runner overwrite each other's entry for
 * the same node; [release] is only called when the editor stops a preview, so a
 * disarm leaves a dead flow behind that [fire] emits into silently; and [fire]
 * returns nothing, while a tile has to say whether the run worked.
 */
class ManualTrigger : Trigger<ManualTriggerConfig, Unit> {

    override val definition = pulseTriggerNode<ManualTriggerConfig>(
        typeId = TYPE_ID.value,
        displayName = "Manual Trigger",
        description = "Starts the workflow when you tap run, or tap its widget or shortcut",
        category = NodeCategory.MANUAL,
        icon = NodeIcon.BOLT,
    )

    override fun activate(
        config: ManualTriggerConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<Unit>> {
        val flow = MutableSharedFlow<NodeOutput<Unit>>(extraBufferCapacity = 1)
        activeFlows[node.id] = flow
        return flow
    }

    companion object {
        val TYPE_ID = NodeTypeId("trigger.manual")

        /** The config key the label is stored under; see `ManualTriggerRef`. */
        const val LABEL_KEY = "label"

        /**
         * Concurrent because the two sides genuinely are: [activate] runs on the
         * engine service's arm coroutine while the editor's preview [fire]s from
         * the main thread. A plain map here was a data race waiting for a user with
         * a macro open in the editor and armed in the background.
         */
        private val activeFlows = ConcurrentHashMap<NodeId, MutableSharedFlow<NodeOutput<Unit>>>()

        /** Called by the ViewModel to fire a manual trigger for the given node. */
        fun fire(nodeId: NodeId) {
            activeFlows[nodeId]?.tryEmit(NodeOutput(Unit))
        }

        /** Removes the flow when the workflow run is cancelled. */
        fun release(nodeId: NodeId) {
            activeFlows.remove(nodeId)
        }
    }
}
