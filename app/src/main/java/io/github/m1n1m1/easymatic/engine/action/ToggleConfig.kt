package io.github.m1n1m1.easymatic.engine.action

import io.github.m1n1m1.easymatic.core.service.OnOff
import io.github.m1n1m1.easymatic.domain.model.config.Label
import kotlinx.serialization.Serializable

/**
 * Shared config for the plain on/off device toggles (`action.wifi`,
 * `action.bluetooth`, `action.auto_rotate`): a single [OnOff] choice. Declared
 * once rather than repeated per node, in the same spirit as `Tier1Helpers` on the
 * trigger side.
 *
 * `action.flashlight` left this class when it grew a third option, because these
 * three write a setting they are never told the previous value of — a "Toggle"
 * here would have nothing to invert. See `FlashlightConfig`.
 */
@Serializable
data class ToggleConfig(
    @Label("State") val state: OnOff = OnOff.ON,
) {
    val enabled: Boolean get() = state.enabled
}
