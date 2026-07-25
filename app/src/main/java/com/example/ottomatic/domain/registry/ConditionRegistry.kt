package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.engine.ConditionNode
import com.example.ottomatic.engine.condition.AirplaneModeCondition
import com.example.ottomatic.engine.condition.BatteryCondition
import com.example.ottomatic.engine.condition.BluetoothCondition
import com.example.ottomatic.engine.condition.ChargingCondition
import com.example.ottomatic.engine.condition.CompareCondition
import com.example.ottomatic.engine.condition.DndCondition
import com.example.ottomatic.engine.condition.RingerModeCondition
import com.example.ottomatic.engine.condition.ScreenCondition
import com.example.ottomatic.engine.condition.WifiCondition

/**
 * Central registry mapping a condition [typeId] to its implementation.
 *
 * This list is the *only* registration step for a new
 * [com.example.ottomatic.engine.ConditionNode], exactly as [ActionRegistry] is
 * for actions: the condition's own file declares its metadata, ports and config
 * fields in a single
 * [com.example.ottomatic.engine.ConditionNodeDefinition].
 *
 * Registering here lights up *both* placements at once — the node appears in the
 * palette to drop on the canvas, and in the add-condition picker to attach to any
 * node. [NodeTypeRegistry] and [ConfigSchemaRegistry] derive their views from
 * these definitions; [ActionRegistry] serves the canvas-placement execution path
 * by wrapping each entry in
 * [com.example.ottomatic.engine.ConditionAsAction].
 */
object ConditionRegistry {

    private val conditions: List<ConditionNode<*>> = listOf(
        AirplaneModeCondition(),
        BatteryCondition(),
        BluetoothCondition(),
        ChargingCondition(),
        CompareCondition(),
        DndCondition(),
        RingerModeCondition(),
        ScreenCondition(),
        WifiCondition(),
    )

    private val byId: Map<NodeTypeId, ConditionNode<*>> = conditions.associateBy { it.typeId }

    fun byId(typeId: NodeTypeId): ConditionNode<*>? = byId[typeId]

    fun all(): List<ConditionNode<*>> = conditions
}
