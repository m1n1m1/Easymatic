package com.example.ottomatic.data.trigger

import com.example.ottomatic.core.model.NodeId
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.ottomatic.core.trigger.TriggerBus
import com.example.ottomatic.core.trigger.TriggerEvent
import com.example.ottomatic.core.trigger.TriggerSource
import com.example.ottomatic.engine.trigger.BatteryDirection

/**
 * Periodic worker that polls the current battery level and emits a
 * [TriggerEvent] when the level crosses the configured threshold in the
 * configured direction. Enqueued by [AndroidTriggerHost.armBatteryLevelPoll];
 * identified by the node id and config passed as input data.
 *
 * Uses SharedPreferences-backed hysteresis so a level hovering near the
 * threshold does not flap: an event is only emitted on the transition from
 * "condition not satisfied" to "condition satisfied".
 *
 * WorkManager persists the request across reboots and process death, so this
 * runs even when the app is killed — at WorkManager's 15-minute minimum
 * interval floor.
 */
class BatteryLevelWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun doWork(): Result {
        val nodeId = inputData.getString(KEY_NODE_ID) ?: return Result.failure()
        val direction = inputData.getString(KEY_DIRECTION)
            ?.let { name -> runCatching { BatteryDirection.valueOf(name) }.getOrNull() }
            ?: BatteryDirection.BELOW
        val threshold = inputData.getInt(KEY_LEVEL, DEFAULT_THRESHOLD)
        val battery = readBattery()
        if (battery.level >= 0) {
            maybeEmit(nodeId, direction, threshold, battery)
        }
        return Result.success()
    }

    private fun maybeEmit(
        nodeId: String,
        direction: BatteryDirection,
        threshold: Int,
        battery: BatterySnapshot,
    ) {
        val nowSatisfied = when (direction) {
            BatteryDirection.ABOVE -> battery.level >= threshold
            BatteryDirection.BELOW -> battery.level <= threshold
        }
        val wasSatisfied = prefs.getBoolean(prefsKey(nodeId, direction, threshold), !nowSatisfied)
        if (nowSatisfied && !wasSatisfied) {
            TriggerBus.emit(
                TriggerEvent(
                    source = TriggerSource.BATTERY,
                    triggerNodeId = NodeId(nodeId),
                    payload = mapOf(
                        ChargingReceiver.KEY_EVENT to EVENT_LEVEL_POLL,
                        ChargingReceiver.KEY_LEVEL to battery.level.toString(),
                        ChargingReceiver.KEY_IS_CHARGING to battery.isCharging.toString(),
                        ChargingReceiver.KEY_PLUGGED to battery.plugged.orEmpty(),
                        ChargingReceiver.KEY_TIMESTAMP to System.currentTimeMillis().toString(),
                    ),
                ),
            )
        }
        prefs.edit { putBoolean(prefsKey(nodeId, direction, threshold), nowSatisfied) }
    }

    private fun readBattery(): BatterySnapshot {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val intent = applicationContext.registerReceiver(null, filter) ?: return BatterySnapshot()
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
        return BatterySnapshot(level = percent, isCharging = isCharging, plugged = plugged)
    }

    private fun prefsKey(nodeId: String, direction: BatteryDirection, threshold: Int): String =
        "$PREFS_KEY_PREFIX$nodeId/${direction.name}/$threshold"

    private data class BatterySnapshot(
        val level: Int = -1,
        val isCharging: Boolean = false,
        val plugged: String? = null,
    )

    companion object {
        const val KEY_NODE_ID = "nodeId"
        const val KEY_DIRECTION = "direction"
        const val KEY_LEVEL = "level"

        const val EVENT_LEVEL_POLL = "level_poll"

        const val WORK_NAME_PREFIX = "ottomatic_battery_level_"

        private const val PREFS_NAME = "ottomatic_battery_level"
        private const val PREFS_KEY_PREFIX = "last_satisfied_"
        private const val DEFAULT_THRESHOLD = 20
        private const val PERCENT_SCALE = 100
    }
}
