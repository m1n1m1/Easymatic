package com.example.ottomatic.engine.trigger

import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.model.items.StopwatchTick
import com.example.ottomatic.domain.model.schema.Item
import com.example.ottomatic.engine.NodeOutput
import com.example.ottomatic.engine.triggerNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow

/**
 * Trigger for `trigger.stopwatch`. Arms a periodic schedule via the host and
 * emits a [StopwatchTick] on each tick, reporting the elapsed time since the
 * trigger was activated.
 *
 * Polling is WorkManager-backed (15-minute floor) so it runs even when the app
 * is killed. The interval is configurable via `intervalMinutes`; if omitted the
 * WorkManager minimum is used.
 *
 * Produces a typed [StopwatchTick] item on the `tick` data port.
 */
class StopwatchTrigger : Trigger<StopwatchTick> {

    override val definition = triggerNode<StopwatchTick>(
        typeId = "trigger.stopwatch",
        displayName = "Stopwatch",
        description = "Ticks on a fixed interval, reporting elapsed time",
        category = NodeCategory.TIME_SCHEDULE,
        iconKey = "timer",
        dataOutputs = listOf(dataOut<StopwatchTick>("tick")),
        encodeData = { tick -> mapOf("tick" to Item.of(tick)) },
    )

    override fun activate(node: WorkflowNode, host: TriggerHost): Flow<NodeOutput<StopwatchTick>> {
        val intervalMinutes = node.config[CONFIG_INTERVAL]?.toLongOrNull() ?: DEFAULT_INTERVAL_MINUTES
        return flow {
            val handle = host.armSchedule(node.id, intervalMinutes, cron = null)
            val startedAt = System.currentTimeMillis()
            try {
                host.busEvents()
                    .filter {
                        it.source == TriggerSource.SCHEDULE && it.triggerNodeId == node.id
                    }
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

    companion object {
        const val CONFIG_INTERVAL = "intervalMinutes"
        const val DEFAULT_INTERVAL_MINUTES = 15L
    }
}
