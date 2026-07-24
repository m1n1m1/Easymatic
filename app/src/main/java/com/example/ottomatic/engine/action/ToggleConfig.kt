package com.example.ottomatic.engine.action

import com.example.ottomatic.core.service.OnOff
import com.example.ottomatic.domain.model.config.Label
import kotlinx.serialization.Serializable

/**
 * Shared config for the plain on/off device toggles (`action.wifi`,
 * `action.bluetooth`, `action.flashlight`, `action.auto_rotate`): a single
 * [OnOff] choice. Declared once rather than repeated per node, in the same
 * spirit as `Tier1Helpers` on the trigger side.
 */
@Serializable
data class ToggleConfig(
    @Label("State") val state: OnOff = OnOff.ON,
) {
    val enabled: Boolean get() = state.enabled
}
