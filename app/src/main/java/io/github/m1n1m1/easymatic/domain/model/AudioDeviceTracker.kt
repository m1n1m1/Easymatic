package io.github.m1n1m1.easymatic.domain.model

import io.github.m1n1m1.easymatic.core.service.AudioDeviceType

/** An external output reported by Android, identified for the lifetime of its connection. */
data class AudioOutput(val id: Int, val type: AudioDeviceType, val name: String)

/** Absorbs the callback's initial device list and suppresses repeated notifications. */
class AudioDeviceTracker {
    private var seeded = false
    private val connected = mutableMapOf<Int, AudioOutput>()

    fun added(devices: List<AudioOutput>): List<AudioOutput> {
        val additions = devices.filter { connected.put(it.id, it) == null }
        if (!seeded) {
            seeded = true
            return emptyList()
        }
        return additions
    }

    fun removed(ids: List<Int>): List<AudioOutput> = ids.mapNotNull(connected::remove)
}
