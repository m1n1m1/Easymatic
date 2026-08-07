package com.example.ottomatic.data.service

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import com.example.ottomatic.core.service.DeviceState
import com.example.ottomatic.core.service.RingerMode
import com.example.ottomatic.domain.model.WifiSsid

/**
 * Android-backed implementation of [DeviceState].
 *
 * Every read is wrapped in `runCatching { }.getOrNull()`: a missing subsystem, a
 * revoked permission or an OEM quirk yields null rather than throwing, which a
 * comparison turns into a false verdict. Reading a device property must never crash
 * the flow that reads it.
 */
@Suppress("TooManyFunctions") // Implements every DeviceState reader.
class AndroidDeviceState(private val context: Context) : DeviceState {

    override fun isWifiEnabled(): Boolean? = runCatching {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiManager.isWifiEnabled
    }.getOrNull()

    /**
     * `getConnectionInfo()` is deprecated from API 31 in favour of pulling a
     * `WifiInfo` off a `NetworkCapabilities`, but that route needs a registered
     * network callback and a `Network` to ask about — machinery for watching
     * *changes*, which is [com.example.ottomatic.data.trigger.WifiNetworkBridge]'s
     * job. This is a synchronous "what is it right now?", the deprecated call still
     * answers it on every supported version, and going through the callback API for
     * a one-shot read would mean registering and tearing down a callback per pull.
     */
    @Suppress("DEPRECATION")
    override fun currentWifiNetwork(): String? = runCatching {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // Blank covers both "not on Wi-Fi" and "not allowed to say", which is one
        // answer to the consumer either way: nothing to compare against.
        WifiSsid.normalise(wifiManager.connectionInfo?.ssid).takeIf { it.isNotBlank() }
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

    override fun isPowerSaveMode(): Boolean? = runCatching {
        val manager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        manager.isPowerSaveMode
    }.getOrNull()

    /**
     * Whether anything headphone-shaped is plugged into the jack or the USB
     * port. `AudioManager.isWiredHeadsetOn` would be the obvious call and is
     * deprecated precisely because it answers about routing rather than about
     * what is attached; enumerating the output devices is the sanctioned way and
     * is also the only one that notices a USB-C headset.
     */
    override fun isHeadsetPlugged(): Boolean? = runCatching {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in WIRED_HEADSET_TYPES }
    }.getOrNull()

    /**
     * Whether the device is docked, from the sticky `ACTION_DOCK_EVENT`
     * broadcast.
     *
     * A device that has never been docked has no sticky intent at all, which
     * reads as undocked rather than as unknown: "never docked" is a fact about
     * the dock state, not a failure to determine it.
     */
    override fun isDocked(): Boolean? = runCatching {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_DOCK_EVENT))
        val state = intent?.getIntExtra(Intent.EXTRA_DOCK_STATE, Intent.EXTRA_DOCK_STATE_UNDOCKED)
        state != null && state != Intent.EXTRA_DOCK_STATE_UNDOCKED
    }.getOrNull()

    override fun isNightMode(): Boolean? = runCatching {
        val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        mode == Configuration.UI_MODE_NIGHT_YES
    }.getOrNull()

    private companion object {
        const val PERCENT = 100

        /** Output devices that mean "headphones are plugged in". */
        val WIRED_HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )

        /** `Settings.Global.ZEN_MODE` is `@hide`; the key itself is stable. */
        const val ZEN_MODE = "zen_mode"
        const val ZEN_MODE_OFF = 0
    }
}
