package io.github.m1n1m1.easymatic.data.trigger

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.trigger.TriggerBus
import io.github.m1n1m1.easymatic.core.trigger.TriggerEvent
import io.github.m1n1m1.easymatic.core.trigger.TriggerSource
import io.github.m1n1m1.easymatic.data.service.AudioDeviceTypes
import io.github.m1n1m1.easymatic.domain.model.AudioDeviceTracker
import io.github.m1n1m1.easymatic.domain.model.AudioOutput

/**
 * One process-lifetime callback, like [WifiNetworkBridge]. The first added-device
 * callback is Android's initial inventory, not a connection event. All callbacks
 * use the main handler, serialising tracker updates even during startup.
 */
class AudioDeviceBridge(context: Context) {
    private val tracker = AudioDeviceTracker()
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            val outputs = addedDevices.mapNotNull { device ->
                AudioDeviceTypes.classify(device.type, device.isSink)?.let { type ->
                    AudioOutput(device.id, type, device.productName.toString())
                }
            }
            tracker.added(outputs).forEach { emit(it, "plugged") }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            tracker.removed(removedDevices.map { it.id }).forEach { emit(it, "unplugged") }
        }
    }

    init {
        runCatching {
            val manager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        }.onFailure { Log.w("Easymatic", "Not watching audio devices", it) }
    }

    private fun emit(device: AudioOutput, event: String) {
        TriggerBus.emit(
            TriggerEvent(
                source = TriggerSource.HARDWARE,
                triggerNodeId = NodeId.BROADCAST,
                payload = mapOf(
                    "triggerType" to "headset",
                    "event" to event,
                    "deviceType" to device.type.name,
                    "detail" to device.name,
                ),
            ),
        )
    }
}
