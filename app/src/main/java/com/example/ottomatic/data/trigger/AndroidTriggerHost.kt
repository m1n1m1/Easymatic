package com.example.ottomatic.data.trigger

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.ottomatic.engine.trigger.ScheduleHandle
import com.example.ottomatic.engine.trigger.TriggerHost
import java.util.concurrent.TimeUnit

/**
 * Android implementation of [TriggerHost]. Supplies real system streams and
 * arms [ScheduleWorker] via WorkManager.
 */
class AndroidTriggerHost(
    context: Context,
) : TriggerHost {

    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun armSchedule(
        nodeId: String,
        intervalMinutes: Long,
        cron: String?,
    ): ScheduleHandle {
        // WorkManager enforces a 15-minute minimum; clamp here for clarity.
        val minutes = intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES)
        val request = PeriodicWorkRequestBuilder<ScheduleWorker>(minutes, TimeUnit.MINUTES)
            .setInputData(workDataOf(ScheduleWorker.KEY_NODE_ID to nodeId))
            .build()
        val workName = ScheduleWorker.WORK_NAME_PREFIX + nodeId
        workManager.enqueueUniquePeriodicWork(workName, ExistingPeriodicWorkPolicy.UPDATE, request)
        return ScheduleHandle { workManager.cancelUniqueWork(workName) }
    }

    private companion object {
        const val MIN_INTERVAL_MINUTES = 15L
    }
}
