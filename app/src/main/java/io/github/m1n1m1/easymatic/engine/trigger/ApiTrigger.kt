package io.github.m1n1m1.easymatic.engine.trigger

import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.config.ApiToken
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Ports
import io.github.m1n1m1.easymatic.domain.registry.API_TRIGGER_TYPE_ID
import io.github.m1n1m1.easymatic.engine.NodeOutput
import io.github.m1n1m1.easymatic.engine.adaptiveTriggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.Serializable

/**
 * Config for `trigger.api`.
 *
 * [inputs]' default **must stay blank**. `NodeSchema.decode` reads a blank config
 * value as absent and substitutes the property default, while
 * [io.github.m1n1m1.easymatic.domain.registry.effectivePorts] reads the raw config and
 * sees blank — so any other default makes the ports drawn on the card disagree with
 * the ones the node actually binds, and makes a deleted row reappear. `ScriptConfig`
 * records the same rule for the same reason.
 *
 * [token] is blank by default in the *declaration* and non-blank in practice: the
 * editor generates one when the node is placed. The distinction matters because a
 * blank token is a meaningful state — *approved apps only* — and the default has to
 * be able to express it, for the node someone deliberately cleared.
 */
@Serializable
data class ApiTriggerConfig(
    @Label("Name other apps see") val label: String = "",
    @Label("Values this call carries") @Ports val inputs: String = "",
    @Label("Key") @ApiToken val token: String = "",
)

/**
 * Trigger for `trigger.api`. Fires when another app, a script or a shortcut asks
 * Easymatic to run this macro.
 *
 * ## It is never activated, and that is the design
 *
 * [activate] returns a flow that nothing ever emits into. A call does not travel
 * through the trigger object at all: it resolves `(macroId, nodeId)` against the
 * repository and goes straight to `runFromTrigger`, which is exactly the road
 * `ACTION_RUN_MANUAL` already takes for a widget tap. Three things follow.
 *
 * The macro **need not be armed** — placing this node is what makes a macro
 * reachable, not switching it on. But a macro whose switch is *off* is still
 * refused ([io.github.m1n1m1.easymatic.domain.model.ApiContract.STATUS_DISABLED]), which
 * is where this parts company with a widget tap and a shortcut, both of which run a
 * disabled macro. The difference is who is looking: somebody tapping a tile can see
 * the macro is off and means it anyway, where a caller three apps away cannot.
 *
 * This is now the same shape [ManualTrigger] has, and it got there second: that one
 * kept a registry of shared flows keyed by node id for the editor's Run button to
 * emit into, and every problem this node was built to avoid was one of its — the
 * editor's preview runner and the engine's armed runner overwriting each other's
 * entry, and a disarm leaving a dead flow behind. Neither trigger keys anything by
 * node id any more.
 *
 * The returned flow is a bare [MutableSharedFlow] rather than `emptyFlow()`
 * deliberately. An empty flow **completes**, and `MacroEngineService.arm` drops a job
 * whose body finished — so a macro whose only trigger is this one would read as
 * unarmed the instant it was armed. A shared flow with no emitters never completes
 * and never emits, which is the honest description of what this trigger does in the
 * background: nothing, until somebody outside calls.
 *
 * ## The key lives in the config
 *
 * Which means it lives in the workflow's JSON file, and is therefore included in
 * Android's backup and duplicated along with the macro when it is copied. That is
 * the accepted cost of not needing a second store the dispatcher would have to open
 * and keep consistent — the node is already loaded to be found. It authorises
 * "start this one macro" and nothing else, and **Regenerate** in the config form is
 * how a key that has been somewhere it should not be gets replaced.
 */
class ApiTrigger : Trigger<ApiTriggerConfig, Unit> {

    override val definition = adaptiveTriggerNode<ApiTriggerConfig>(
        typeId = API_TRIGGER_TYPE_ID.value,
        displayName = "Called by Another App",
        description = "Starts the workflow when another app, a script or a shortcut asks Easymatic to run it",
        category = NodeCategory.MANUAL,
        icon = NodeIcon.HTTP,
    )

    override fun activate(
        config: ApiTriggerConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<Unit>> = MutableSharedFlow()

    companion object {
        val TYPE_ID = API_TRIGGER_TYPE_ID

        /** The config key the caller-facing name is stored under. */
        const val LABEL_KEY = "label"
    }
}
