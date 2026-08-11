package com.example.ottomatic.sample

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.plugin.PluginArm
import com.example.ottomatic.plugin.PluginContext
import com.example.ottomatic.plugin.PluginTrigger
import com.example.ottomatic.plugin.pluginTriggerNode
import kotlinx.serialization.Serializable

/** `EXTRA_TEMPERATURE` is reported in tenths of a degree Celsius. */
private const val TENTHS_PER_DEGREE = 10.0

/** Config for the sample's trigger. */
@Serializable
data class TemperatureConfig(
    @Label("Fire above (°C)") val above: Int = 35,
)

/** What the trigger hands downstream. */
@Serializable
data class TemperatureReading(
    val celsius: Double,
    val threshold: Int,
)

/**
 * A plugin trigger: register something, hand back the handle that releases it.
 *
 * [arm] is not suspending and takes a callback rather than returning a `Flow`, so a
 * plugin author needs no coroutines at all to write one. [emit] may be called from any
 * thread, at any time, until the returned [PluginArm] is disarmed.
 *
 * Two lifetime facts are worth knowing and are the SDK's job to make survivable rather
 * than the author's. The host **re-arms rather than resumes** after a binding drops —
 * so a trigger never has to reason about reconnection, it is simply armed again. And
 * `BaseOttomaticPluginService.onDestroy` disarms everything still registered, so a
 * receiver like this one cannot outlive the reason it was registered even if the
 * process is torn down without a disarm.
 *
 * The battery broadcast is a sticky one, so registering delivers the current reading
 * immediately. That is a genuine property worth showing: a plugin trigger may fire as
 * soon as it is armed, and the host is built for that.
 */
class TemperatureTrigger : PluginTrigger<TemperatureConfig, TemperatureReading> {

    override val definition = pluginTriggerNode<TemperatureConfig, TemperatureReading>(
        typeId = "battery_hot",
        displayName = "Battery gets hot",
        description = "Starts when the battery goes above a temperature — a worked example of a plugin trigger",
        icon = NodeIcon.BATTERY_LEVEL,
        output = dataOut<TemperatureReading>("reading", label = "Reading"),
    )

    override fun arm(
        config: TemperatureConfig,
        context: PluginContext,
        emit: (TemperatureReading) -> Unit,
    ): PluginArm {
        val receiver = object : BroadcastReceiver() {
            private var wasAbove = false

            override fun onReceive(unused: Context?, intent: Intent?) {
                val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                if (tenths == null || tenths == Int.MIN_VALUE) return
                val celsius = tenths / TENTHS_PER_DEGREE
                val above = celsius > config.above
                // Edge, not level: the sticky broadcast repeats every few seconds, and a
                // macro that fires on every one of them is not what "gets hot" means.
                if (above && !wasAbove) emit(TemperatureReading(celsius, config.above))
                wasAbove = above
            }
        }
        context.android.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return PluginArm { runCatching { context.android.unregisterReceiver(receiver) } }
    }
}
