package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.pulseTriggerNode
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
 * Trigger for `trigger.manual`. Never fires while armed — it is *run*, not listened to.
 *
 * Every manual run — the button on the node's own card, a home-screen tile, a
 * deck cell, a launcher shortcut — goes straight to `runFromTrigger` against the
 * graph, which needs no activation at all and therefore works whether or not the
 * macro is armed. So [activate] has nothing to register and hands back a flow
 * that never emits.
 *
 * There used to be a registry of shared flows here, keyed by node id, that the
 * editor's Run button emitted into. It was keyed by node id *alone*, so the
 * editor's preview runner and the engine's armed runner overwrote each other's
 * entry for the same node; its `release` ran only when the editor stopped a
 * preview, so a disarm left a dead flow behind that a fire emitted into
 * silently; and firing returned nothing, while a tile has to say whether the run
 * worked. Two of the bugs it caused were visible from the outside: an armed
 * macro could not be run by hand at all, and a graph holding any other trigger
 * ran that one instead.
 *
 * Never-emitting rather than `emptyFlow()`, for the reason [ApiTrigger] gives:
 * an empty flow **completes**, and `MacroEngineService.arm` drops a job whose
 * body finished — so a macro whose only trigger is a manual one would read as
 * unarmed the instant it was armed. A shared flow with no emitters never
 * completes and never emits, which is the honest description of what this
 * trigger does while armed.
 */
class ManualTrigger : Trigger<ManualTriggerConfig, Unit> {

    override val definition = pulseTriggerNode<ManualTriggerConfig>(
        typeId = TYPE_ID.value,
        displayName = "Manual Trigger",
        description = "Starts the workflow when you tap its run button, widget or shortcut",
        category = NodeCategory.MANUAL,
        icon = NodeIcon.BOLT,
    )

    override fun activate(
        config: ManualTriggerConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<Unit>> = MutableSharedFlow()

    companion object {
        val TYPE_ID = NodeTypeId("trigger.manual")

        /** The config key the label is stored under; see `ManualTriggerRef`. */
        const val LABEL_KEY = "label"
    }
}
