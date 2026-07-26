package com.example.ottomatic.engine.action

import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.ComparisonOperator
import com.example.ottomatic.domain.model.config.ComparisonType
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.wildcardDataIn
import com.example.ottomatic.domain.registry.IF_SOURCE_IN
import com.example.ottomatic.domain.registry.IF_TYPE_ID
import com.example.ottomatic.domain.registry.IF_VALUE_IN
import com.example.ottomatic.engine.ExecOutputs
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.ExecutionRoute
import com.example.ottomatic.engine.NodeInput
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.RawAction
import com.example.ottomatic.engine.adaptiveNode
import com.example.ottomatic.engine.evaluateCompare
import kotlinx.serialization.Serializable

/**
 * Config for `action.if`, and for every attached gate.
 *
 * [source] is a *source spec* rather than a plain value — see
 * [com.example.ottomatic.engine.ValueSource]. That single field is what lets one
 * comparison serve both placements: a placed node leaves it blank to use its wired
 * `source` port, and an attached gate names either a host input port or a value
 * node to read on demand.
 *
 * The static form derived from this class is narrowed at design time by
 * [com.example.ottomatic.domain.registry.effectiveConfigSchema] (placed form) and
 * [com.example.ottomatic.domain.registry.effectiveConditionSchema] (attached
 * form): [source] becomes a dropdown of the available sources, [field] a dropdown
 * of the inspected struct's fields, [operator] is filtered to those valid for the
 * inspected type, and [value] is retyped to match.
 */
@Serializable
data class CompareConfig(
    @Label("Type") val type: ComparisonType = ComparisonType.AUTO,
    @Label("Field") val field: String = "",
    @Label("Operator") val operator: ComparisonOperator = ComparisonOperator.EQUALS,
    @Label("Source") val source: String = "",
    @Label("Compare against") val value: String = "",
)

/**
 * `action.if` — the graph's only comparison, and its only conditional branch.
 *
 * Placed on the canvas it is the adaptive if-node: its `source`/`value` port
 * schemas and its config form are resolved from whatever is wired into it, and it
 * routes execution to `true` or `false`. Attached to another node as a gate, the
 * *same* comparison decides whether that node runs at all — but there it is not
 * this action that runs. Both call [evaluateCompare] directly, so a gate's verdict
 * is a real Boolean rather than an exec route read backwards, and the two
 * placements cannot drift apart.
 *
 * It replaces the former `condition.*` family entirely: those nodes welded a
 * device reader to a comparison, one weld per property. The readers are now
 * [com.example.ottomatic.engine.ValueNode]s and this is the comparison.
 */
class IfAction : RawAction<CompareConfig> {

    override val definition = adaptiveNode<CompareConfig>(
        typeId = IF_TYPE_ID.value,
        displayName = "If",
        description = "Routes execution by comparing a value against another",
        category = NodeCategory.FLOW_CONTROL,
        icon = NodeIcon.SPLIT,
        wildcardInputs = listOf(
            wildcardDataIn(IF_SOURCE_IN.value, label = "Source"),
            wildcardDataIn(IF_VALUE_IN.value, label = "Compare against"),
        ),
        execOutputs = ExecOutputs.BRANCH,
    )

    override suspend fun executeRaw(
        config: CompareConfig,
        input: NodeInput,
        context: ExecutionContext,
    ): NodeOutput<Map<PortName, Item>> {
        val matched = evaluateCompare(config, input, context)
        return NodeOutput(
            value = emptyMap(),
            route = if (matched) ExecutionRoute.TRUE else ExecutionRoute.FALSE,
        )
    }
}
