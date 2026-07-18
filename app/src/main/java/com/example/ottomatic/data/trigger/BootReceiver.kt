package com.example.ottomatic.data.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.ottomatic.core.trigger.TriggerBus
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.core.trigger.TriggerSource

/**
 * Receives BOOT_COMPLETED (and equivalent) broadcasts. Emits a [TriggerEvent]
 * for boot triggers and re-arms any persisted schedules.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            -> {
                TriggerBus.emit(
                    TriggerEvent(
                        source = TriggerSource.BOOT,
                        triggerNodeId = BootTriggerBridge.NODE_ID_SENTINEL,
                    ),
                )
            }
        }
    }
}

/** Shared constants between the receiver and the engine-side BootTrigger. */
object BootTriggerBridge {
    const val NODE_ID_SENTINEL = "*"
}
