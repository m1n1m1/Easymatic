package com.example.ottomatic.data.service

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import com.example.ottomatic.core.service.DeviceState
import com.example.ottomatic.core.service.RingerMode

/**
 * Android-backed implementation of [DeviceState].
 *
 * Every read is wrapped in `runCatching { }.getOrNull()`: a missing subsystem, a
 * revoked permission or an OEM quirk yields null rather than throwing, which a
 * comparison turns into a false verdict. Reading a device property must never crash
 * the flow that reads it.
 */
class AndroidDeviceState(private val context: Context) : DeviceState {

    override fun isWifiEnabled(): Boolean? = runCatching {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiManager.isWifiEnabled
    }.getOrNull()

    override fun isBluetoothEnabled(): Boolean? = runCatching {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter?.isEnabled
    }.getOrNull()

    override fun isAirplaneMode(): Boolean? = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON) != 0
    }.getOrNull()

    override fun isCharging(): Boolean? = runCatching {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        manager.isCharging
    }.getOrNull()

    /**
     * Battery percentage. [BatteryManager.BATTERY_PROPERTY_CAPACITY] reports -1
     * on devices that do not implement it, so fall back to the sticky
     * `ACTION_BATTERY_CHANGED` broadcast, which every device posts.
     */
    override fun batteryLevel(): Int? = runCatching {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..PERCENT }
            ?: stickyBatteryLevel()
    }.getOrNull()

    private fun stickyBatteryLevel(): Int? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level < 0 || scale <= 0) null else level * PERCENT / scale
    }

    override fun isScreenOn(): Boolean? = runCatching {
        val manager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        manager.isInteractive
    }.getOrNull()

    override fun isDndEnabled(): Boolean? = runCatching {
        Settings.Global.getInt(context.contentResolver, ZEN_MODE) != ZEN_MODE_OFF
    }.getOrNull()

    override fun ringerMode(): RingerMode? = runCatching {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (manager.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> RingerMode.SILENT
            AudioManager.RINGER_MODE_VIBRATE -> RingerMode.VIBRATE
            else -> RingerMode.NORMAL
        }
    }.getOrNull()

    private companion object {
        const val PERCENT = 100

        /** `Settings.Global.ZEN_MODE` is `@hide`; the key itself is stable. */
        const val ZEN_MODE = "zen_mode"
        const val ZEN_MODE_OFF = 0
    }
}
