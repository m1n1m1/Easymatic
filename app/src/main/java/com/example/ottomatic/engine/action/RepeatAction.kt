package com.example.ottomatic.engine.action

import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.service.LogLevel
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.domain.registry.LOOP_INDEX_OUT
import com.example.ottomatic.domain.registry.REPEAT_TYPE_ID
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.LoopAction
import com.example.ottomatic.engine.MAX_ITERATIONS
import com.example.ottomatic.engine.NodeInput
import com.example.ottomatic.engine.loopNode
import kotlinx.serialization.Serializable

/**
 * Config for `action.repeat`.
 *
 * `@Wired`, because "as many times as the response said" is the interesting case;
 * typed into the form it is the ordinary "three times".
 */
@Serializable
data class RepeatConfig(
    @Label("How many times") @Wired val times: Int = 3,
)

/**
 * `action.repeat` — runs its body a fixed number of times.
 *
 * Unreal's `For Loop` without the first-index / last-index pair: counting always
 * starts at 0 and runs [RepeatConfig.times] passes, because choosing a start index
 * is a programmer's affordance and nobody automating a phone wants to be asked. The
 * position is on `Index` for whoever needs it — most bodies do not.
 *
 * Shares its exec shape with [ForEachAction]: `Loop body` per pass, `Completed`
 * once afterwards, both pointing forward.
 *
 * A count of zero or less runs the body zero times and still pulses `Completed`,
 * matching what an empty list does. The count is clamped to [MAX_ITERATIONS] here
 * rather than left to the executor's backstop because this is the one loop whose
 * size is a *number the user typed* — a stray zero would otherwise build a list of
 * a billion maps before anything got the chance to refuse it.
 */
class RepeatAction : LoopAction<RepeatConfig> {

    override val definition = loopNode<RepeatConfig>(
        typeId = REPEAT_TYPE_ID.value,
        displayName = "Repeat",
        // "loop" is in every loop's description on purpose: it is the word people
        // search for, and none of the three display names contains it.
        description = "Loops the following steps a set number of times — repeat 10 times, and so on",
        category = NodeCategory.FLOW_CONTROL,
        icon = NodeIcon.LOOP,
        extraPorts = listOf(
            Port(LOOP_INDEX_OUT, PortKind.DATA, Direction.OUT, ItemSchema.Primitive(Int::class), label = "Index"),
        ),
    )

    override suspend fun iterations(
        config: RepeatConfig,
        input: NodeInput,
        context: ExecutionContext,
    ): List<Map<PortName, Item>> {
        val wanted = config.times
        if (wanted > MAX_ITERATIONS) {
            context.log("Repeat: capped at $MAX_ITERATIONS of $wanted", LogLevel.WARN)
        }
        val count = wanted.coerceIn(0, MAX_ITERATIONS)
        return (0 until count).map { index ->
            mapOf(LOOP_INDEX_OUT to Item(index, ItemSchema.Primitive(Int::class)))
        }
    }
}
