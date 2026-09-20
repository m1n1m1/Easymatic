package io.github.m1n1m1.easymatic.data.service

import android.media.AudioDeviceInfo
import io.github.m1n1m1.easymatic.core.service.AudioDeviceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioDeviceTypesTest {
    @Test
    fun `headphones speakers hearing aids and broadcasts share the Bluetooth filter`() {
        listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_HEARING_AID, AudioDeviceInfo.TYPE_BLE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_BROADCAST, AudioDeviceInfo.TYPE_BLE_CENTRAL,
            AudioDeviceInfo.TYPE_BLE_CENTRAL_BROADCAST,
        ).forEach { assertEquals(AudioDeviceType.BLUETOOTH, AudioDeviceTypes.classify(it)) }
    }

    @Test
    fun `external connectors map to their shared categories`() {
        val cases = mapOf(
            AudioDeviceType.WIRED to listOf(
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_AUX_LINE,
            ),
            AudioDeviceType.USB to listOf(
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY,
            ),
            AudioDeviceType.DIGITAL to listOf(
                AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC,
                AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_IP, AudioDeviceInfo.TYPE_BUS,
                AudioDeviceInfo.TYPE_MULTICHANNEL_GROUP,
            ),
            AudioDeviceType.DOCK to listOf(AudioDeviceInfo.TYPE_DOCK, AudioDeviceInfo.TYPE_DOCK_ANALOG),
        )
        cases.forEach { (category, types) ->
            types.forEach { assertEquals(category, AudioDeviceTypes.classify(it)) }
        }
    }

    @Test
    fun `built in virtual unknown and input only devices do not count as external outputs`() {
        listOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_TELEPHONY, AudioDeviceInfo.TYPE_REMOTE_SUBMIX, AudioDeviceInfo.TYPE_UNKNOWN,
        ).forEach { assertNull(AudioDeviceTypes.classify(it)) }
        assertNull(AudioDeviceTypes.classify(AudioDeviceInfo.TYPE_USB_DEVICE, isSink = false))
    }
}
