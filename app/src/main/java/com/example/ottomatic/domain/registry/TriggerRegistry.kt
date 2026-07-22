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
        // Tier 0 — engine-internal triggers.
        add(com.example.ottomatic.engine.trigger.EmptyTrigger())
        add(com.example.ottomatic.engine.trigger.AppInitTrigger())
        add(com.example.ottomatic.engine.trigger.MacroFinishedTrigger())
        add(com.example.ottomatic.engine.trigger.MacroEnabledTrigger())
        add(com.example.ottomatic.engine.trigger.ModeChangeTrigger())
        add(com.example.ottomatic.engine.trigger.VariableChangeTrigger())
        add(com.example.ottomatic.engine.trigger.StopwatchTrigger())
        add(com.example.ottomatic.engine.trigger.SleepTrigger())
        // Tier 1 — broadcast-receiver triggers.
        add(com.example.ottomatic.engine.trigger.WifiStateTrigger())
        add(com.example.ottomatic.engine.trigger.BluetoothTrigger())
        add(com.example.ottomatic.engine.trigger.BluetoothConnectTrigger())
        add(com.example.ottomatic.engine.trigger.AirplaneModeTrigger())
        add(com.example.ottomatic.engine.trigger.CallStateTrigger())
        add(com.example.ottomatic.engine.trigger.HeadsetTrigger())
        add(com.example.ottomatic.engine.trigger.UsbDeviceTrigger())
        add(com.example.ottomatic.engine.trigger.DockTrigger())
        add(com.example.ottomatic.engine.trigger.ScreenTrigger())
        add(com.example.ottomatic.engine.trigger.UserPresentTrigger())
        add(com.example.ottomatic.engine.trigger.RingerModeTrigger())
        add(com.example.ottomatic.engine.trigger.PowerSaveTrigger())
        add(com.example.ottomatic.engine.trigger.TimezoneChangeTrigger())
        add(com.example.ottomatic.engine.trigger.LocaleChangeTrigger())
        add(com.example.ottomatic.engine.trigger.DateChangeTrigger())
        add(com.example.ottomatic.engine.trigger.ShutdownTrigger())
        add(com.example.ottomatic.engine.trigger.TimeTickTrigger())
        add(com.example.ottomatic.engine.trigger.AppInstalledTrigger())
        add(com.example.ottomatic.engine.trigger.MediaButtonTrigger())
        add(com.example.ottomatic.engine.trigger.MediaMountTrigger())
    }

    private val byId: Map<String, Trigger> = triggers.associateBy { it.typeId }

    fun byId(typeId: String): Trigger? = byId[typeId]

    fun all(): List<Trigger> = triggers
}
