package io.github.m1n1m1.easymatic.data.trigger

import io.github.m1n1m1.easymatic.core.model.NodeId
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.m1n1m1.easymatic.core.trigger.TriggerBus
import io.github.m1n1m1.easymatic.core.trigger.TriggerEvent
import io.github.m1n1m1.easymatic.core.trigger.TriggerSource

/**
 * Periodic worker that emits a [TriggerEvent] for a scheduled trigger node.
 * Enqueued by [AndroidTriggerHost.armSchedule]; identified by the node id
 * passed as input data.
 */
class ScheduleWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val nodeId = inputData.getString(KEY_NODE_ID) ?: return Result.failure()
        TriggerBus.emit(
            TriggerEvent(
                source = TriggerSource.SCHEDULE,
                triggerNodeId = NodeId(nodeId),
            ),
        )
        return Result.success()
    }

    companion object {
        const val KEY_NODE_ID = "nodeId"
        const val WORK_NAME_PREFIX = "easymatic_schedule_"
    }
}
