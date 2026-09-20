package io.github.m1n1m1.easymatic.data.service

import android.media.AudioDeviceInfo
import io.github.m1n1m1.easymatic.core.service.AudioDeviceType

/** Same classification for the synchronous value and the connection callbacks. */
internal object AudioDeviceTypes {
    // These are inlined integer identifiers, not API calls. Older Android versions
    // never report the newer types, but can safely compare against their identifiers.
    @Suppress("InlinedApi")
    fun classify(type: Int, isSink: Boolean = true): AudioDeviceType? = if (!isSink) null else when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_AUX_LINE -> AudioDeviceType.WIRED

        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> AudioDeviceType.USB

        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        AudioDeviceInfo.TYPE_BLE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_CENTRAL,
        AudioDeviceInfo.TYPE_BLE_CENTRAL_BROADCAST -> AudioDeviceType.BLUETOOTH

        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_IP,
        AudioDeviceInfo.TYPE_BUS,
        AudioDeviceInfo.TYPE_MULTICHANNEL_GROUP -> AudioDeviceType.DIGITAL

        AudioDeviceInfo.TYPE_DOCK,
        AudioDeviceInfo.TYPE_DOCK_ANALOG -> AudioDeviceType.DOCK

        else -> null
    }
}
