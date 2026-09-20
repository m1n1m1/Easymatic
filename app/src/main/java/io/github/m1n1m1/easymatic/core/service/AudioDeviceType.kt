package io.github.m1n1m1.easymatic.core.service

import io.github.m1n1m1.easymatic.domain.model.config.Label
import kotlinx.serialization.Serializable

/** Shared filter for external audio outputs; built-in and virtual routes are excluded. */
@Serializable
enum class AudioDeviceType {
    ANY,
    @Label("Wired (AUX)") WIRED,
    @Label("USB") USB,
    BLUETOOTH,
    @Label("HDMI / Digital") DIGITAL,
    DOCK,
    ;

    fun matches(type: AudioDeviceType): Boolean = this == ANY || this == type
}
