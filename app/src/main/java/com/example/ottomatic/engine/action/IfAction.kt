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
 * Config for `action.if`.
 *
 * [source] is a *source spec* rather than a plain value — see
 * [com.example.ottomatic.engine.ValueSource]. Blank means "read my own wired
 * `source` port"; `val:<typeId>` names a value node to read on demand, which needs
 * no edge at all.
 *
 * The static form derived from this class is narrowed at design time by
 * [com.example.ottomatic.domain.registry.effectiveConfigSchema]: [source] becomes a
 * dropdown of the available sources, [field] a dropdown of the inspected struct's
 * fields, [operator] is filtered to those valid for the inspected type, and [value]
 * is retyped to match.
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
 * An adaptive node: its `source`/`value` port schemas and its config form are
 * resolved from whatever is wired into it, and it routes execution to `true` or
 * `false`. The comparison itself lives in [evaluateCompare] rather than here, so
 * what "greater than" means is decided independently of how the answer is routed.
 *
 * Running a node only under some condition is expressed by placing this upstream of
 * it. There is deliberately no way to attach a condition to a node instead: a
 * hidden branch is the one thing a node graph should never have.
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
        extraPorts = listOf(
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
