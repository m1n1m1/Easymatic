package com.example.ottomatic.domain.registry

import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.action.BreakStructAction
import com.example.ottomatic.engine.action.ConditionAction
import com.example.ottomatic.engine.action.DelayAction
import com.example.ottomatic.engine.action.DndAction
import com.example.ottomatic.engine.action.HttpAction
import com.example.ottomatic.engine.action.NotifyAction
import com.example.ottomatic.engine.action.VolumeAction
import com.example.ottomatic.engine.action.WifiAction

/**
 * Central registry mapping an action [typeId] to its executable [Action].
 *
 * Mirrors [NodeTypeRegistry] which holds only metadata. New [Action]
 * implementations must be added here. The single adaptive
 * [BreakStructAction] splits any `@Serializable` struct into its fields at
 * runtime; per-field data inputs are exposed directly on each node via
 * [WorkflowNode.exposedInputs] (no dedicated make-struct action is needed).
 */
object ActionRegistry {

    private val actions: List<Action> = listOf(
        ConditionAction(),
        DelayAction(),
        DndAction(),
        HttpAction(),
        NotifyAction(),
        VolumeAction(),
        WifiAction(),
        BreakStructAction(),
    )

    private val byId: Map<String, Action> = actions.associateBy { it.typeId }

    fun byId(typeId: String): Action? = byId[typeId]

    fun all(): List<Action> = actions
}
