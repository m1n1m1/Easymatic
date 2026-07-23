package com.example.ottomatic.data.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.ottomatic.core.trigger.TriggerBus
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.data.BootFailureNotifier
import com.example.ottomatic.data.BootFailureStore
import com.example.ottomatic.engine.service.MacroEngineService

/**
 * Receives BOOT_COMPLETED (and equivalent) broadcasts. Emits a [TriggerEvent]
 * for boot triggers and starts [MacroEngineService] to re-arm any persisted-
 * enabled macros so background execution resumes after a reboot.
 *
 * Engine re-arming is best-effort under Android 12+ background FGS-start
 * restrictions: `BOOT_COMPLETED` carries a temporary exemption on stock Android,
 * but restricted OEM builds may block the start. If the start throws, a
 * notification is posted (see [BootFailureNotifier]) and the persisted flag in
 * [BootFailureStore] remains set so the next app launch surfaces a battery-
 * optimisation prompt.
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
                // Optimistically mark that a prompt may be needed; the engine
                // service clears this in onCreate once it has actually started.
                // If the start is blocked below (or the service is killed before
                // onCreate), the flag survives and MainActivity surfaces a prompt.
                BootFailureStore.markPending(context)
                runCatching {
                    MacroEngineService.start(context, MacroEngineService.ACTION_REARM_ALL)
                }.onFailure {
                    // startForegroundService threw — most commonly
                    // ForegroundServiceStartNotAllowedException on Android 12+
                    // when the BOOT_COMPLETED exemption is not honoured. Surface
                    // a notification; the persisted flag stays set so the next
                    // app launch also prompts to disable battery optimisation.
                    BootFailureNotifier.notify(context)
                }
            }
        }
    }
}

/** Shared constants between the receiver and the engine-side BootTrigger. */
object BootTriggerBridge {
    const val NODE_ID_SENTINEL = "*"
}
