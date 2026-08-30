package io.github.m1n1m1.easymatic.data.trigger

import io.github.m1n1m1.easymatic.core.model.NodeId
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import io.github.m1n1m1.easymatic.core.trigger.TriggerBus
import io.github.m1n1m1.easymatic.core.trigger.TriggerEvent
import io.github.m1n1m1.easymatic.core.trigger.TriggerSource

/**
 * Receives power connected/disconnected broadcasts and pushes a [TriggerEvent]
 * onto the [TriggerBus]. Manifest-registered so it wakes a killed app.
 *
 * - `ACTION_POWER_CONNECTED` → payload `event = "charging_started"`
 * - `ACTION_POWER_DISCONNECTED` → payload `event = "charging_stopped"`
 *
 * Level and plugged source are read from the sticky `ACTION_BATTERY_CHANGED`
 * intent (fetched via [registerReceiver] with a null receiver, which returns
 * the sticky broadcast without subscribing).
 *
 * No permission required — both actions are protected system broadcasts.
 */
class ChargingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> EVENT_CHARGING_STARTED
            Intent.ACTION_POWER_DISCONNECTED -> EVENT_CHARGING_STOPPED
            else -> return
        }
        val battery = readStickyBattery(context)
        TriggerBus.emit(
            TriggerEvent(
                source = TriggerSource.BATTERY,
                triggerNodeId = NodeId.BROADCAST,
                payload = mapOf(
                    KEY_EVENT to event,
                    KEY_LEVEL to battery.level.toString(),
                    KEY_IS_CHARGING to battery.isCharging.toString(),
                    KEY_PLUGGED to battery.plugged.orEmpty(),
                    KEY_TIMESTAMP to System.currentTimeMillis().toString(),
                ),
            ),
        )
    }

    private fun readStickyBattery(context: Context): BatterySnapshot {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val intent = context.registerReceiver(null, filter) ?: return BatterySnapshot()
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) (level * PERCENT_SCALE / scale) else -1
        val pluggedRaw = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val plugged = when (pluggedRaw) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> null
        }
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return BatterySnapshot(
            level = percent,
            isCharging = isCharging,
            plugged = plugged,
        )
    }

    private data class BatterySnapshot(
        val level: Int = -1,
        val isCharging: Boolean = false,
        val plugged: String? = null,
    )

    companion object {
        // System receivers don't know which workflow node they belong to.
        // The ChargingTrigger fans this out to all charging trigger nodes by
        // matching on source + event filter.

        const val EVENT_CHARGING_STARTED = "charging_started"
        const val EVENT_CHARGING_STOPPED = "charging_stopped"

        const val KEY_EVENT = "event"
        const val KEY_LEVEL = "level"
        const val KEY_IS_CHARGING = "isCharging"
        const val KEY_PLUGGED = "plugged"
        const val KEY_TIMESTAMP = "timestamp"

        private const val PERCENT_SCALE = 100
    }
}
