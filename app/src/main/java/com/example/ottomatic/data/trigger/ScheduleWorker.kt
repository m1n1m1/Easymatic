package com.example.ottomatic.data.trigger

import com.example.ottomatic.core.model.NodeId
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.ottomatic.core.trigger.TriggerBus
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.core.trigger.TriggerSource

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
        const val WORK_NAME_PREFIX = "ottomatic_schedule_"
    }
}
