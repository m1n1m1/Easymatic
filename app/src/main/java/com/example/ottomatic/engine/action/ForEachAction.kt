package com.example.ottomatic.engine.action

import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.config.NoConfig
import com.example.ottomatic.domain.model.listDataIn
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.domain.model.schema.flatViewFor
import com.example.ottomatic.domain.registry.FOR_EACH_ITEM_OUT
import com.example.ottomatic.domain.registry.FOR_EACH_LIST_IN
import com.example.ottomatic.domain.registry.FOR_EACH_TYPE_ID
import com.example.ottomatic.domain.registry.LOOP_INDEX_OUT
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.LoopAction
import com.example.ottomatic.engine.NodeInput
import com.example.ottomatic.engine.loopNode

/**
 * `action.for_each` — runs its body once for every element of a list.
 *
 * The graph's way of saying "do this to all of them", and the reason lists are
 * worth having at all. `Loop body` pulses once per element with that element on
 * `Item` and its position on `Index`; `Completed` pulses once afterwards.
 *
 * Both outputs point **forward**. This is Unreal's `For Each Loop` rather than
 * n8n's `Loop Over Items`, which asks the user to wire the end of the body back
 * into the loop: that edge is an execution cycle, which `GraphValidator` reports as
 * an error and the executor's `onPath` guard refuses to walk. Keeping the shape
 * acyclic means iteration costs the rest of the engine nothing, and the picture
 * still reads left to right.
 *
 * `Item` is typed from whatever list is wired in — the same backwards walk
 * `action.break` does, in `effectivePorts` — so looping a list of numbers gives a
 * number port and a mis-wired body is a refused drop rather than a runtime
 * surprise. With nothing wired it is a wildcard, which is what lets the node be
 * placed before the thing that feeds it.
 *
 * An empty list runs the body zero times and still pulses `Completed`: "there was
 * nothing to send" is an outcome, not a failure, and a macro that stopped dead
 * there would look exactly like one wired wrong.
 */
class ForEachAction : LoopAction<NoConfig> {

    override val definition = loopNode<NoConfig>(
        typeId = FOR_EACH_TYPE_ID.value,
        displayName = "Repeat for each item",
        description = "Loops over a list, running the following steps once for every item in it",
        category = NodeCategory.FLOW_CONTROL,
        icon = NodeIcon.LOOP,
        // Declared even though `effectivePorts` retypes `item`: the palette that
        // opens when a wire is dragged into empty space reads declared ports.
        extraPorts = listOf(
            listDataIn(FOR_EACH_LIST_IN.value, label = "List"),
            Port(FOR_EACH_ITEM_OUT, PortKind.DATA, Direction.OUT, ItemSchema.Wildcard, label = "Item"),
            Port(LOOP_INDEX_OUT, PortKind.DATA, Direction.OUT, ItemSchema.Primitive(Int::class), label = "Index"),
        ),
        hasDynamicPorts = true,
    )

    override suspend fun iterations(
        config: NoConfig,
        input: NodeInput,
        context: ExecutionContext,
    ): List<Map<PortName, Item>> {
        val item = input.item(FOR_EACH_LIST_IN)
        val elements = item?.value as? List<*>
        if (elements == null) {
            // Not an error: an unwired loop is a half-built macro, and the schema
            // check already refuses anything that is not a list at drop time.
            context.log("For each: nothing to loop over", LogLevel.WARN)
            return emptyList()
        }
        val element = (item.schema as? ItemSchema.ListSchema)?.element ?: ItemSchema.Wildcard
        // Deliberately not truncated here: the list is already in memory, and
        // letting the executor apply the cap is what gets the "stopped after N of
        // M" line into the run log instead of silently walking a shorter list.
        return elements.mapIndexed { index, value ->
            mapOf(
                FOR_EACH_ITEM_OUT to Item(value, element, flatViewFor(value, element)),
                LOOP_INDEX_OUT to Item(index, ItemSchema.Primitive(Int::class)),
            )
        }
    }
}
