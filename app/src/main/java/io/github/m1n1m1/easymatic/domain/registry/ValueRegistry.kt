package io.github.m1n1m1.easymatic.domain.registry

import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.engine.ValueNode
import io.github.m1n1m1.easymatic.engine.value.AirplaneModeValue
import io.github.m1n1m1.easymatic.engine.value.BatteryLevelValue
import io.github.m1n1m1.easymatic.engine.value.BluetoothValue
import io.github.m1n1m1.easymatic.engine.value.CalendarBusyValue
import io.github.m1n1m1.easymatic.engine.value.CalendarNextValue
import io.github.m1n1m1.easymatic.engine.value.CallActiveValue
import io.github.m1n1m1.easymatic.engine.value.ChargingValue
import io.github.m1n1m1.easymatic.engine.value.CurrentCallValue
import io.github.m1n1m1.easymatic.engine.value.DarkModeValue
import io.github.m1n1m1.easymatic.engine.value.DeviceOrientationValue
import io.github.m1n1m1.easymatic.engine.value.DndValue
import io.github.m1n1m1.easymatic.engine.value.DockValue
import io.github.m1n1m1.easymatic.engine.value.HaStateValue
import io.github.m1n1m1.easymatic.engine.value.AudioDeviceConnectedValue
import io.github.m1n1m1.easymatic.engine.value.LatestImageValue
import io.github.m1n1m1.easymatic.engine.value.LatestScreenshotValue
import io.github.m1n1m1.easymatic.engine.value.LightLevelValue
import io.github.m1n1m1.easymatic.engine.value.MediaPlayingValue
import io.github.m1n1m1.easymatic.engine.value.MqttTopicValue
import io.github.m1n1m1.easymatic.engine.value.NfcValue
import io.github.m1n1m1.easymatic.engine.value.NowPlayingValue
import io.github.m1n1m1.easymatic.engine.value.NowValue
import io.github.m1n1m1.easymatic.engine.value.PowerSaveValue
import io.github.m1n1m1.easymatic.engine.value.ProximityValue
import io.github.m1n1m1.easymatic.engine.value.RecordingValue
import io.github.m1n1m1.easymatic.engine.value.RingerModeValue
import io.github.m1n1m1.easymatic.engine.value.ScreenOnValue
import io.github.m1n1m1.easymatic.engine.value.SpeakingValue
import io.github.m1n1m1.easymatic.engine.value.TorchValue
import io.github.m1n1m1.easymatic.engine.value.VariableValue
import io.github.m1n1m1.easymatic.engine.value.WifiNetworkValue
import io.github.m1n1m1.easymatic.engine.value.WifiValue

/**
 * Central registry mapping a value [typeId] to its reader.
 *
 * This list is the *only* registration step for a new
 * [io.github.m1n1m1.easymatic.engine.ValueNode], exactly as [ActionRegistry] is for
 * actions: the reader's own file declares its metadata, output port and config in
 * a single [io.github.m1n1m1.easymatic.engine.ValueNodeDefinition].
 *
 * Registering here lights up every use of a value at once — it appears in the
 * palette to drop on the canvas as a data source, in the drag-to-create suggestions
 * for any compatible data input ([suggestionsFor]), and in `action.if`'s source
 * dropdown, where it can be read with no edge drawn to it (see
 * [io.github.m1n1m1.easymatic.domain.model.ValueSource]). [NodeTypeRegistry] and
 * [ConfigSchemaRegistry] derive their views from these definitions.
 *
 * Unlike [ActionRegistry] there is no execution bridge: a value node is never
 * pulsed. The executor reads it directly when a consumer needs it.
 */
object ValueRegistry {

    private val values: List<ValueNode<*, *>> = listOf(
        AirplaneModeValue(),
        BatteryLevelValue(),
        BluetoothValue(),
        CalendarBusyValue(),
        CalendarNextValue(),
        CallActiveValue(),
        ChargingValue(),
        CurrentCallValue(),
        DarkModeValue(),
        DeviceOrientationValue(),
        DndValue(),
        DockValue(),
        HaStateValue(),
        AudioDeviceConnectedValue(),
        LatestImageValue(),
        LatestScreenshotValue(),
        LightLevelValue(),
        MediaPlayingValue(),
        MqttTopicValue(),
        NfcValue(),
        NowPlayingValue(),
        NowValue(),
        PowerSaveValue(),
        ProximityValue(),
        RecordingValue(),
        RingerModeValue(),
        ScreenOnValue(),
        SpeakingValue(),
        TorchValue(),
        VariableValue(),
        WifiNetworkValue(),
        WifiValue(),
    )

    private val byId: Map<NodeTypeId, ValueNode<*, *>> = values.associateBy { it.typeId }

    fun byId(typeId: NodeTypeId): ValueNode<*, *>? = byId[typeId]

    fun all(): List<ValueNode<*, *>> = values
}
