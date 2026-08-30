package io.github.m1n1m1.easymatic.data.wait

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Manifest-registered receiver for the one-shot alarms [AndroidWaits] arms.
 *
 * Deliberately **not** [io.github.m1n1m1.easymatic.data.trigger.AlarmReceiver]: that
 * one publishes a `SCHEDULE` event onto the trigger bus keyed by node id, which
 * any `trigger.schedule` node sharing that id would collect. A wait resumes one
 * specific suspended coroutine and concerns nothing else in the process, so it
 * goes straight to the token that identifies it.
 *
 * An alarm whose token is no longer in the registry is dropped in silence, which
 * is the ordinary outcome after the process has been restarted: there is nothing
 * left to resume, and the alarm was cancelled by whoever is gone.
 */
class WaitAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getLongExtra(EXTRA_TOKEN, NO_TOKEN)
        if (token == NO_TOKEN) return
        AndroidWaits.release(token)
    }

    companion object {
        const val ACTION_WAIT = "io.github.m1n1m1.easymatic.WAIT_ALARM"
        const val EXTRA_TOKEN = "token"
        private const val NO_TOKEN = -1L
    }
}
