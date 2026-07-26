package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.engine.ValueNode
import com.example.ottomatic.engine.value.AirplaneModeValue
import com.example.ottomatic.engine.value.BatteryLevelValue
import com.example.ottomatic.engine.value.BluetoothValue
import com.example.ottomatic.engine.value.ChargingValue
import com.example.ottomatic.engine.value.DndValue
import com.example.ottomatic.engine.value.RingerModeValue
import com.example.ottomatic.engine.value.ScreenOnValue
import com.example.ottomatic.engine.value.WifiValue

/**
 * Central registry mapping a value [typeId] to its reader.
 *
 * This list is the *only* registration step for a new
 * [com.example.ottomatic.engine.ValueNode], exactly as [ActionRegistry] is for
 * actions: the reader's own file declares its metadata, output port and config in
 * a single [com.example.ottomatic.engine.ValueNodeDefinition].
 *
 * Registering here lights up all three uses of a value at once — it appears in the
 * palette to drop on the canvas as a data source, in the drag-to-create
 * suggestions for any compatible data input ([suggestionsFor]), and in the
 * add-condition picker as a comparable source for an attached gate (see
 * [com.example.ottomatic.engine.ValueSource]). [NodeTypeRegistry] and
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
        ChargingValue(),
        DndValue(),
        RingerModeValue(),
        ScreenOnValue(),
        WifiValue(),
    )

    private val byId: Map<NodeTypeId, ValueNode<*, *>> = values.associateBy { it.typeId }

    fun byId(typeId: NodeTypeId): ValueNode<*, *>? = byId[typeId]

    fun all(): List<ValueNode<*, *>> = values
}
