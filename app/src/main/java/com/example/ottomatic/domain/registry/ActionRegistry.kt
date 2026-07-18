package com.example.ottomatic.domain.registry

import com.example.ottomatic.engine.Action
import com.example.ottomatic.engine.action.ConditionAction
import com.example.ottomatic.engine.action.DelayAction
import com.example.ottomatic.engine.action.HttpAction
import com.example.ottomatic.engine.action.NotifyAction
import com.example.ottomatic.engine.action.WifiAction

/**
 * Central registry mapping an action [typeId] to its executable [Action].
 *
 * Mirrors [NodeTypeRegistry] which holds only metadata. New [Action]
 * implementations must be added here.
 */
object ActionRegistry {

    private val actions: List<Action> = listOf(
        ConditionAction(),
        DelayAction(),
        HttpAction(),
        NotifyAction(),
        WifiAction(),
    )

    private val byId: Map<String, Action> = actions.associateBy { it.typeId }

    fun byId(typeId: String): Action? = byId[typeId]

    fun all(): List<Action> = actions
}
