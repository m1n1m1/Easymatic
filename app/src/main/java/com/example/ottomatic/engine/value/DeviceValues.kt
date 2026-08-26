package com.example.ottomatic.engine.value

import com.example.ottomatic.core.permissions.PermissionRequirement
import com.example.ottomatic.core.permissions.Permissions
import com.example.ottomatic.core.permissions.PrerequisiteType
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
 * to its own form value, and a comparison over it fails closed.
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

/**
 * `value.wifi_network` — the name of the Wi-Fi network currently joined.
 *
 * The one value node that declares a permission, and the reason the contract was
 * written to allow it: naming a network needs `ACCESS_FINE_LOCATION`, but the read is
 * otherwise exactly as cheap and repeatable as [BatteryLevelValue]'s. Declaring the
 * grant is what puts the node in the Problems panel and the Permissions screen when
 * it has not been given — undeclared, it would simply read null forever with nothing
 * anywhere saying why.
 *
 * Reads null both when the device is off Wi-Fi and when the platform withholds the
 * name, so a comparison over it fails closed either way.
 */
class WifiNetworkValue : DeviceValue<String>() {
    override val definition = valueNode<NoConfig, String>(
        typeId = "value.wifi_network",
        displayName = "Wi-Fi network",
        description = "The name of the Wi-Fi network the device is currently connected to",
        category = NodeCategory.VALUE_CONNECTIVITY,
        icon = NodeIcon.WIFI,
        output = dataOut("ssid", label = "Network"),
        permissions = listOf(
            PermissionRequirement(
                manifestPermission = Permissions.ACCESS_FINE_LOCATION.manifest,
                type = PrerequisiteType.RUNTIME,
                rationaleKey = "wifi.network",
            ),
        ),
    )

    override fun readValue(context: ExecutionContext) = context.deviceState.currentWifiNetwork()
}

/**
 * `value.nfc` — whether the NFC radio is switched on.
 *
 * Two things about this one are exceptions, and both are deliberate.
 *
 * It has **no trigger counterpart**, as [TorchValue] does not. The pairing rule runs
 * the other way — every trigger over a readable state earns a value — and there is
 * no `trigger.nfc_state` because the platform publishes no adapter-state broadcast
 * worth arming a macro on. A value with no trigger costs nothing and answers a real
 * question, so the absence of the other half is not a reason to leave it out.
 *
 * It also declares **no permission**, unlike [WifiNetworkValue], and the reason is
 * sharper than "it does not need one": a node whose entire job is to answer *"is the
 * radio on?"* must not be badged in the Problems panel for the radio being off. That
 * is the node working. The prerequisite belongs on `trigger.nfc`, which genuinely
 * cannot fire without it.
 */
class NfcValue : DeviceValue<Boolean>() {
    override val definition = valueNode<NoConfig, Boolean>(
        typeId = "value.nfc",
        displayName = "NFC enabled",
        description = "Whether NFC is currently switched on",
        category = NodeCategory.VALUE_CONNECTIVITY,
        icon = NodeIcon.NFC,
        output = dataOut("enabled", label = "Enabled"),
    )

    override fun readValue(context: ExecutionContext) = context.deviceState.isNfcEnabled()
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

/** `value.power_save` — whether battery saver is on. */
class PowerSaveValue : DeviceValue<Boolean>() {
    override val definition = valueNode<NoConfig, Boolean>(
        typeId = "value.power_save",
        displayName = "Power save mode",
        description = "Whether battery saver is currently on",
        category = NodeCategory.VALUE_POWER,
        icon = NodeIcon.POWER_SAVE,
        output = dataOut("enabled", label = "On"),
    )

    override fun readValue(context: ExecutionContext) = context.deviceState.isPowerSaveMode()
}

/** `value.headset` — whether wired headphones are plugged in. */
class HeadsetValue : DeviceValue<Boolean>() {
    override val definition = valueNode<NoConfig, Boolean>(
        typeId = "value.headset",
        displayName = "Headset plugged in",
        description = "Whether a wired or USB headset is currently plugged in",
        category = NodeCategory.VALUE_CONNECTIVITY,
        icon = NodeIcon.HEADSET,
        output = dataOut("plugged", label = "Plugged in"),
    )

    override fun readValue(context: ExecutionContext) = context.deviceState.isHeadsetPlugged()
}

/** `value.dock` — whether the device is docked. */
class DockValue : DeviceValue<Boolean>() {
    override val definition = valueNode<NoConfig, Boolean>(
        typeId = "value.dock",
        displayName = "Docked",
        description = "Whether the device is currently sitting in a dock",
        category = NodeCategory.VALUE_CONNECTIVITY,
        icon = NodeIcon.DOCK,
        output = dataOut("docked", label = "Docked"),
    )

    override fun readValue(context: ExecutionContext) = context.deviceState.isDocked()
}

/** `value.dark_mode` — whether the device is in night mode. */
class DarkModeValue : DeviceValue<Boolean>() {
    override val definition = valueNode<NoConfig, Boolean>(
        typeId = "value.dark_mode",
        displayName = "Dark theme",
        description = "Whether the device is currently in dark theme (night mode)",
        category = NodeCategory.VALUE_DEVICE,
        icon = NodeIcon.DARK_MODE,
        output = dataOut("enabled", label = "On"),
    )

    override fun readValue(context: ExecutionContext) = context.deviceState.isNightMode()
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

/**
 * `value.torch` — whether the camera torch is lit.
 *
 * The read half of `action.flashlight`, and what its **Toggle** option resolves
 * against. It follows the torch wherever it was lit from — a quick-settings tile,
 * another app, a macro — because the platform pushes every change into the cache
 * behind [com.example.ottomatic.core.service.DeviceState.isTorchOn] rather than
 * this node tracking what Ottomatic itself did.
 *
 * Like [NfcValue] it declares **no permission**, for the same reason: a node whose
 * whole job is to answer *"is the light on?"* must not be badged in the Problems
 * panel for the light being off. Reading the state needs no grant either — only
 * `action.flashlight`, which changes it, does.
 *
 * Reads null until the platform has reported a torch mode, which covers a phone
 * with no flash unit and one whose camera another app currently holds. A
 * comparison over null fails closed, so "if the torch is on" is false rather than
 * guessed.
 */
class TorchValue : DeviceValue<Boolean>() {
    override val definition = valueNode<NoConfig, Boolean>(
        typeId = "value.torch",
        displayName = "Flashlight on",
        description = "Whether the camera torch (flashlight) is currently lit",
        category = NodeCategory.VALUE_DEVICE,
        icon = NodeIcon.BOLT,
        output = dataOut("on", label = "On"),
    )

    override fun readValue(context: ExecutionContext) = context.deviceState.isTorchOn()
}
