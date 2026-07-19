package com.example.ottomatic.domain.registry

import com.example.ottomatic.engine.trigger.Trigger

/**
 * Central registry mapping a trigger [typeId] to its executable [Trigger].
 *
 * Mirrors [NodeTypeRegistry] which holds only metadata. This object holds
 * the behaviour. New [Trigger] implementations must be added here.
 */
object TriggerRegistry {

    private val triggers: List<Trigger> = buildList {
        add(com.example.ottomatic.engine.trigger.ManualTrigger())
        add(com.example.ottomatic.engine.trigger.ScheduleTrigger())
        add(com.example.ottomatic.engine.trigger.SmsTrigger())
        add(com.example.ottomatic.engine.trigger.NotificationTrigger())
        add(com.example.ottomatic.engine.trigger.BootTrigger())
        add(com.example.ottomatic.engine.trigger.ChargingTrigger())
        add(com.example.ottomatic.engine.trigger.BatteryLevelTrigger())
        add(com.example.ottomatic.engine.trigger.GeofenceTrigger())
    }

    private val byId: Map<String, Trigger> = triggers.associateBy { it.typeId }

    fun byId(typeId: String): Trigger? = byId[typeId]

    fun all(): List<Trigger> = triggers
}
