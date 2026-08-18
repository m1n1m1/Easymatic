package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.engine.ValueNode
import com.example.ottomatic.engine.value.AirplaneModeValue
import com.example.ottomatic.engine.value.BatteryLevelValue
import com.example.ottomatic.engine.value.BluetoothValue
import com.example.ottomatic.engine.value.CalendarBusyValue
import com.example.ottomatic.engine.value.CalendarNextValue
import com.example.ottomatic.engine.value.ChargingValue
import com.example.ottomatic.engine.value.DarkModeValue
import com.example.ottomatic.engine.value.DeviceOrientationValue
import com.example.ottomatic.engine.value.DndValue
import com.example.ottomatic.engine.value.DockValue
import com.example.ottomatic.engine.value.HaStateValue
import com.example.ottomatic.engine.value.HeadsetValue
import com.example.ottomatic.engine.value.LatestImageValue
import com.example.ottomatic.engine.value.LatestScreenshotValue
import com.example.ottomatic.engine.value.LightLevelValue
import com.example.ottomatic.engine.value.MqttTopicValue
import com.example.ottomatic.engine.value.NfcValue
import com.example.ottomatic.engine.value.NowValue
import com.example.ottomatic.engine.value.PowerSaveValue
import com.example.ottomatic.engine.value.ProximityValue
import com.example.ottomatic.engine.value.RecordingValue
import com.example.ottomatic.engine.value.RingerModeValue
import com.example.ottomatic.engine.value.ScreenOnValue
import com.example.ottomatic.engine.value.VariableValue
import com.example.ottomatic.engine.value.WifiNetworkValue
import com.example.ottomatic.engine.value.WifiValue

/**
 * Central registry mapping a value [typeId] to its reader.
 *
 * This list is the *only* registration step for a new
 * [com.example.ottomatic.engine.ValueNode], exactly as [ActionRegistry] is for
 * actions: the reader's own file declares its metadata, output port and config in
 * a single [com.example.ottomatic.engine.ValueNodeDefinition].
 *
 * Registering here lights up every use of a value at once — it appears in the
 * palette to drop on the canvas as a data source, in the drag-to-create suggestions
 * for any compatible data input ([suggestionsFor]), and in `action.if`'s source
 * dropdown, where it can be read with no edge drawn to it (see
 * [com.example.ottomatic.domain.model.ValueSource]). [NodeTypeRegistry] and
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
        ChargingValue(),
        DarkModeValue(),
        DeviceOrientationValue(),
        DndValue(),
        DockValue(),
        HaStateValue(),
        HeadsetValue(),
        LatestImageValue(),
        LatestScreenshotValue(),
        LightLevelValue(),
        MqttTopicValue(),
        NfcValue(),
        NowValue(),
        PowerSaveValue(),
        ProximityValue(),
        RecordingValue(),
        RingerModeValue(),
        ScreenOnValue(),
        VariableValue(),
        WifiNetworkValue(),
        WifiValue(),
    )

    private val byId: Map<NodeTypeId, ValueNode<*, *>> = values.associateBy { it.typeId }

    fun byId(typeId: NodeTypeId): ValueNode<*, *>? = byId[typeId]

    fun all(): List<ValueNode<*, *>> = values
}
