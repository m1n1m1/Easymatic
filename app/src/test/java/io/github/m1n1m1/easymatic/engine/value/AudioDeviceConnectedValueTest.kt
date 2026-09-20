package io.github.m1n1m1.easymatic.engine.value

import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.core.service.AudioDeviceType
import io.github.m1n1m1.easymatic.core.service.DeviceState
import io.github.m1n1m1.easymatic.core.service.UnknownDeviceState
import io.github.m1n1m1.easymatic.engine.DefaultExecutionContext
import io.github.m1n1m1.easymatic.engine.RecordingSystemServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioDeviceConnectedValueTest {
    private val value = AudioDeviceConnectedValue()

    @Test
    fun `the configured device filter reaches the device reader`() = runBlocking {
        val state = object : DeviceState by UnknownDeviceState {
            override fun isAudioDeviceConnected(type: AudioDeviceType): Boolean = type.matches(AudioDeviceType.USB)
        }
        val context = DefaultExecutionContext(systemServices = RecordingSystemServices(), deviceState = state)
        for (type in AudioDeviceType.entries) {
            val config = value.definition.schema.decode(mapOf(ConfigKey("deviceType") to type.name))
            assertEquals(type == AudioDeviceType.ANY || type == AudioDeviceType.USB, value.read(config, context))
        }
    }

    @Test
    fun `old config free values default to any external output`() {
        assertEquals(AudioDeviceConfig(), value.definition.schema.decode(emptyMap()))
    }
}
