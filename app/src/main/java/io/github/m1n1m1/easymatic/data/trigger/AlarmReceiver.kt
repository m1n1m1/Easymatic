package io.github.m1n1m1.easymatic.data.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.trigger.TriggerBus
import io.github.m1n1m1.easymatic.core.trigger.TriggerEvent
import io.github.m1n1m1.easymatic.core.trigger.TriggerSource

/**
 * Manifest-registered receiver for the one-shot exact alarms armed by
 * [AndroidTriggerHost.armAlarm], used by `trigger.schedule` in its
 * "at a time of day" mode.
 *
 * Emits the same `SCHEDULE`-sourced [TriggerEvent] shape as [ScheduleWorker], so
 * the trigger filters both mechanisms identically. Alarms are one-shot: the
 * trigger re-arms the next occurrence after each event.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val nodeId = intent.getStringExtra(EXTRA_NODE_ID) ?: return
        TriggerBus.emit(
            TriggerEvent(
                source = TriggerSource.SCHEDULE,
                triggerNodeId = NodeId(nodeId),
                payload = mapOf(KEY_EVENT to EVENT_ALARM),
            ),
        )
    }

    companion object {
        const val ACTION_ALARM = "io.github.m1n1m1.easymatic.SCHEDULE_ALARM"
        const val EXTRA_NODE_ID = "nodeId"
        const val KEY_EVENT = "event"
        const val EVENT_ALARM = "alarm"
    }
}
