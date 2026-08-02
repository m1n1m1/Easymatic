package com.example.ottomatic.engine.action

import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.domain.model.wildcardDataIn
import com.example.ottomatic.domain.registry.IF_SOURCE_IN
import com.example.ottomatic.domain.registry.IF_VALUE_IN
import com.example.ottomatic.domain.registry.LOOP_INDEX_OUT
import com.example.ottomatic.domain.registry.WHILE_TYPE_ID
import com.example.ottomatic.engine.ConditionalLoopAction
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.NodeInput
import com.example.ottomatic.engine.evaluateCompare
import com.example.ottomatic.engine.loopNode

/**
 * `action.while` — repeats for as long as a comparison holds.
 *
 * The third loop, and the one for iterating *freely*: `action.for_each` needs a
 * list and `action.repeat` needs a count, but "keep trying until it works", "keep
 * going while the battery is above 20%" and "until the counter reaches ten" state
 * neither up front.
 *
 * **It reuses `action.if`'s comparison rather than growing a second one.** The
 * config *is* [CompareConfig], the ports are the same `source`/`value` pair, the
 * form is narrowed by the same code, and the answer comes from the same
 * [evaluateCompare]. There is one definition of what "greater than" means in this
 * graph and this does not add another — the only thing that differs is what happens
 * with the answer, which here is "go round again" rather than "take the true
 * branch". That also means the whole `val:<typeId>` mechanism comes for free: the
 * condition can read a value node or a variable with **no edge drawn to it**, which
 * is what makes the ordinary counter loop expressible without wiring.
 *
 * Exec shape is the other two loops': `body` per pass, `completed` once afterwards,
 * both pointing forward, nothing wired back in.
 *
 * **The condition is re-read every pass** (see `WorkflowExecutor.runConditionalLoop`),
 * which is the difference between this and a `for each` — the latter deliberately
 * snapshots its list. It is also the only reason this terminates: an
 * `action.set_variable` in the body is visible to the next check.
 *
 * A condition that is false to begin with runs the body zero times and pulses
 * `completed`, matching an empty list. One that never goes false is stopped at
 * `MAX_ITERATIONS` with a warning naming what happened — the safeguard that matters
 * most here, because this is the one loop whose length nobody states.
 */
class WhileAction : ConditionalLoopAction<CompareConfig> {

    override val definition = loopNode<CompareConfig>(
        typeId = WHILE_TYPE_ID.value,
        displayName = "Repeat while",
        description = "Loops the following steps for as long as a comparison holds",
        category = NodeCategory.FLOW_CONTROL,
        icon = NodeIcon.LOOP,
        extraPorts = listOf(
            wildcardDataIn(IF_SOURCE_IN.value, label = "Source"),
            wildcardDataIn(IF_VALUE_IN.value, label = "Compare against"),
            Port(LOOP_INDEX_OUT, PortKind.DATA, Direction.OUT, ItemSchema.Primitive(Int::class), label = "Index"),
        ),
        // Its source/value schemas are resolved from the graph, exactly as
        // `action.if`'s are, so the two share `comparisonEffectivePorts`.
        hasDynamicPorts = true,
    )

    override suspend fun nextPass(
        config: CompareConfig,
        input: NodeInput,
        context: ExecutionContext,
        pass: Int,
    ): Map<PortName, Item>? {
        if (!evaluateCompare(config, input, context)) return null
        return mapOf(LOOP_INDEX_OUT to Item(pass, ItemSchema.Primitive(Int::class)))
    }
}
