package com.example.ottomatic.engine.value

import com.example.ottomatic.core.service.RingerMode
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.NoConfig
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.ValueNode
import com.example.ottomatic.engine.valueNode

/**
 * The device readers, one value node per [com.example.ottomatic.core.service.DeviceState]
 * member.
 *
 * These are the *only* declaration of each device property as a readable value.
 * They replace the former `condition.*` family: a question like "is the battery
 * above 50%?" is now `action.if` comparing `value.battery`, so the property and
 * the comparison are declared once each instead of once per pairing.
 *
 * Every reader is config-free and returns null when the subsystem cannot be read,
 * which the executor logs and treats as "no item" — the consumer then falls back
 * to its own form value, and a gate over it fails closed.
 */

/**
 * Base for a value node reading one nullable device property.
 *
 * Only [readValue] and the [definition] metadata differ between readers, so the
 * eight nodes below are declarations rather than implementations.
 */
abstract class DeviceValue<O : Any> internal constructor() : ValueNode<NoConfig, O> {

    /** The current value, or null when it cannot be read. */
    protected abstract fun readValue(context: ExecutionContext): O?

    override suspend fun read(config: NoConfig, context: ExecutionContext): O? = readValue(context)
}

/** `value.battery` — the battery charge percentage. */
class BatteryLevelValue : DeviceValue<Int>() {
    override val definition = valueNode<NoConfig, Int>(
        typeId = "value.battery",
        displayName = "Battery level",
        description = "The current battery charge as a percentage in 0..100",
        category = NodeCategory.VALUE_POWER,
        icon = NodeIcon.BATTERY_LEVEL,
        output = dataOut("level", label = "Level (%)"),
    )

    override fun readValue(context: ExecutionContext) = context.deviceState.batteryLevel()
}

/** `value.charging` — whether the device is charging. */
class ChargingValue : DeviceValue<Boolean>() {
    override val definition = valueNode<NoConfig, Boolean>(
        typeId = "value.charging",
        displayName = "Charging",
        description = "Whether the device is currently charging",
        category = NodeCategory.VALUE_POWER,
        icon = NodeIcon.BATTERY_CHARGING,
        output = dataOut("charging", label = "Charging"),
    )

    override fun readValue(context: ExecutionContext) = context.deviceState.isCharging()
}

/** `value.wifi` — whether Wi-Fi is enabled. */
class WifiValue : DeviceValue<Boolean>() {
    override val definition = valueNode<NoConfig, Boolean>(
        typeId = "value.wifi",
        displayName = "Wi-Fi enabled",
        description = "Whether Wi-Fi is currently enabled",
        category = NodeCategory.VALUE_CONNECTIVITY,
        icon = NodeIcon.WIFI,
        output = dataOut("enabled", label = "Enabled"),
    )

    override fun readValue(context: ExecutionContext) = context.deviceState.isWifiEnabled()
}

/** `value.bluetooth` — whether Bluetooth is enabled. */
class BluetoothValue : DeviceValue<Boolean>() {
    override val definition = valueNode<NoConfig, Boolean>(
        typeId = "value.bluetooth",
        displayName = "Bluetooth enabled",
        description = "Whether Bluetooth is currently enabled",
        category = NodeCategory.VALUE_CONNECTIVITY,
        icon = NodeIcon.BLUETOOTH,
        output = dataOut("enabled", label = "Enabled"),
    )

    override fun readValue(context: ExecutionContext) = context.deviceState.isBluetoothEnabled()
}

/** `value.airplane` — whether airplane mode is on. */
class AirplaneModeValue : DeviceValue<Boolean>() {
    override val definition = valueNode<NoConfig, Boolean>(
        typeId = "value.airplane",
        displayName = "Airplane mode",
        description = "Whether airplane mode is currently on",
        category = NodeCategory.VALUE_CONNECTIVITY,
        icon = NodeIcon.BOLT,
        output = dataOut("enabled", label = "On"),
    )

    override fun readValue(context: ExecutionContext) = context.deviceState.isAirplaneMode()
}

/** `value.screen` — whether the screen is on. */
class ScreenOnValue : DeviceValue<Boolean>() {
    override val definition = valueNode<NoConfig, Boolean>(
        typeId = "value.screen",
        displayName = "Screen on",
        description = "Whether the screen is currently on and interactive",
        category = NodeCategory.VALUE_DEVICE,
        icon = NodeIcon.BOLT,
        output = dataOut("on", label = "On"),
    )

    override fun readValue(context: ExecutionContext) = context.deviceState.isScreenOn()
}

/** `value.dnd` — whether Do-Not-Disturb is active. */
class DndValue : DeviceValue<Boolean>() {
    override val definition = valueNode<NoConfig, Boolean>(
        typeId = "value.dnd",
        displayName = "Do Not Disturb",
        description = "Whether Do-Not-Disturb is currently active",
        category = NodeCategory.VALUE_DEVICE,
        icon = NodeIcon.DND,
        output = dataOut("enabled", label = "Active"),
    )

    override fun readValue(context: ExecutionContext) = context.deviceState.isDndEnabled()
}

/** `value.ringer` — the current ringer mode. */
class RingerModeValue : DeviceValue<RingerMode>() {
    override val definition = valueNode<NoConfig, RingerMode>(
        typeId = "value.ringer",
        displayName = "Ringer mode",
        description = "The device's current ringer mode",
        category = NodeCategory.VALUE_DEVICE,
        icon = NodeIcon.VOLUME,
        output = dataOut("mode", label = "Mode"),
    )

    override fun readValue(context: ExecutionContext) = context.deviceState.ringerMode()
}
