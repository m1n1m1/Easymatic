package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.model.PortName
import io.github.m1n1m1.easymatic.core.service.LogLevel
import io.github.m1n1m1.easymatic.domain.model.Direction
import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.Port
import io.github.m1n1m1.easymatic.domain.model.PortKind
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Wired
import io.github.m1n1m1.easymatic.domain.model.schema.Item
import io.github.m1n1m1.easymatic.domain.model.schema.ItemSchema
import io.github.m1n1m1.easymatic.domain.registry.LOOP_INDEX_OUT
import io.github.m1n1m1.easymatic.domain.registry.REPEAT_TYPE_ID
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.LoopAction
import io.github.m1n1m1.easymatic.engine.MAX_ITERATIONS
import io.github.m1n1m1.easymatic.engine.NodeInput
import io.github.m1n1m1.easymatic.engine.loopNode
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
