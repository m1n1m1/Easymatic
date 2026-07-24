package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.StopwatchTick
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable

/**
 * Config for `trigger.stopwatch`. The interval was previously read without
 * being declared, so it could not be edited in the generated form.
 */
@Serializable
data class StopwatchConfig(
    @Label("Tick interval (minutes, minimum 15)") val intervalMinutes: Long = DEFAULT_INTERVAL_MINUTES,
)

/**
 * Trigger for `trigger.stopwatch`. Arms a periodic schedule via the host and
 * emits a [StopwatchTick] on each tick, reporting the elapsed time since the
 * trigger was activated.
 *
 * Polling is WorkManager-backed (15-minute floor) so it runs even when the app
 * is killed.
 *
 * Produces a typed [StopwatchTick] item on the `tick` data port.
 */
class StopwatchTrigger : Trigger<StopwatchConfig, StopwatchTick> {

    override val definition = triggerNode<StopwatchConfig, StopwatchTick>(
        typeId = "trigger.stopwatch",
        displayName = "Stopwatch",
        description = "Ticks on a fixed interval, reporting elapsed time",
        category = NodeCategory.TIME_SCHEDULE,
        icon = NodeIcon.TIMER,
        output = dataOut<StopwatchTick>("tick", label = "Tick"),
    )

    override fun activate(
        config: StopwatchConfig,
        node: WorkflowNode,
        host: TriggerHost,
    ): Flow<NodeOutput<StopwatchTick>> = flow {
        val handle = host.armSchedule(node.id, config.intervalMinutes, cron = null)
        val startedAt = System.currentTimeMillis()
        try {
            host.busEvents()
                .filter { it.source == TriggerSource.SCHEDULE && it.triggerNodeId == node.id }
                .collect { bus ->
                    emit(
                        NodeOutput(
                            StopwatchTick(
                                elapsedMs = bus.firedAtEpochMs - startedAt,
                                tickAt = bus.firedAtEpochMs,
                            ),
                        ),
                    )
                }
        } finally {
            handle.cancel()
        }
    }
}
