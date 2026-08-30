package io.github.m1n1m1.easymatic.data.trigger

import io.github.m1n1m1.easymatic.core.model.NodeId
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import io.github.m1n1m1.easymatic.core.trigger.TriggerBus
import io.github.m1n1m1.easymatic.core.trigger.TriggerEvent
import io.github.m1n1m1.easymatic.core.trigger.TriggerSource

/**
 * Runtime-registered bridge for broadcasts that **cannot** be declared in the
 * manifest:
 * - [Intent.ACTION_SCREEN_ON] / [Intent.ACTION_SCREEN_OFF]
 * - [Intent.ACTION_USER_PRESENT] (device unlocked)
 * - [Intent.ACTION_TIME_TICK] (fires roughly every minute)
 *
 * Android requires these to be registered via [Context.registerReceiver] at
 * runtime; manifest intent-filters are silently ignored. This class mirrors
 * [AppLifecycleBridge] in structure but pushes events directly onto
 * [TriggerBus] (like [SystemStateReceiver]) so engine triggers can consume
 * them via [io.github.m1n1m1.easymatic.engine.trigger.TriggerHost.busEvents].
 *
 * Payload contract:
 * - `triggerType` ∈ `"screen"`, `"user_present"`, `"time_tick"`
 * - `event` — `"on"`, `"off"`, `"present"`, `"tick"`
 * - `timestamp` — epoch ms
 */
class ScreenBroadcastBridge(context: Context) {

    private val appContext = context.applicationContext

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            val (source, triggerType, event) = when (action) {
                Intent.ACTION_SCREEN_ON -> Triple(
                    TriggerSource.DISPLAY,
                    "screen",
                    "on",
                )
                Intent.ACTION_SCREEN_OFF -> Triple(
                    TriggerSource.DISPLAY,
                    "screen",
                    "off",
                )
                Intent.ACTION_USER_PRESENT -> Triple(
                    TriggerSource.DISPLAY,
                    "user_present",
                    "present",
                )
                Intent.ACTION_TIME_TICK -> Triple(
                    TriggerSource.SYSTEM,
                    "time_tick",
                    "tick",
                )
                else -> return
            }
            TriggerBus.emit(
                TriggerEvent(
                    source = source,
                    triggerNodeId = NodeId.BROADCAST,
                    payload = mapOf(
                        KEY_TRIGGER_TYPE to triggerType,
                        KEY_EVENT to event,
                        KEY_TIMESTAMP to System.currentTimeMillis().toString(),
                    ),
                ),
            )
        }
    }

    private val filter = IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_ON)
        addAction(Intent.ACTION_SCREEN_OFF)
        addAction(Intent.ACTION_USER_PRESENT)
        addAction(Intent.ACTION_TIME_TICK)
    }

    init {
        appContext.registerReceiver(receiver, filter)
    }

    companion object {

        const val KEY_TRIGGER_TYPE = "triggerType"
        const val KEY_EVENT = "event"
        const val KEY_TIMESTAMP = "timestamp"
    }
}
