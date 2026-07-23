package com.example.ottomatic.domain.registry

import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.NodeTypeDefinition
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.items.BatteryState
import com.example.ottomatic.domain.model.items.DndState
import com.example.ottomatic.domain.model.items.GeofenceEvent
import com.example.ottomatic.domain.model.items.HttpResponseItem
import com.example.ottomatic.domain.model.items.MediaEvent
import com.example.ottomatic.domain.model.items.ModeChange
import com.example.ottomatic.domain.model.items.NotificationEvent
import com.example.ottomatic.domain.model.items.PackageEvent
import com.example.ottomatic.domain.model.items.ScheduleFire
import com.example.ottomatic.domain.model.items.SmsMessage
import com.example.ottomatic.domain.model.items.StopwatchTick
import com.example.ottomatic.domain.model.items.SystemState
import com.example.ottomatic.domain.model.items.VariableChange
import com.example.ottomatic.domain.model.items.VolumeState
import com.example.ottomatic.domain.model.items.WifiState
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.domain.model.schema.schemaOf

/** EXECUTION input port. */
private fun execIn(name: String = "in"): Port =
    Port(name = name, kind = PortKind.EXECUTION, direction = Direction.IN)

/** EXECUTION output port. */
private fun execOut(name: String = "out"): Port =
    Port(name = name, kind = PortKind.EXECUTION, direction = Direction.OUT)

/** DATA output port with a schema derived from a `@Serializable` type [T]. */
private inline fun <reified T : Any> dataOut(
    name: String,
    cardinality: com.example.ottomatic.domain.model.Cardinality = com.example.ottomatic.domain.model.Cardinality.ONE,
): Port = Port(
    name = name,
    kind = PortKind.DATA,
    direction = Direction.OUT,
    schema = schemaOf<T>(),
    cardinality = cardinality,
)

/**
 * Central registry for all available node types (triggers and actions).
 *
 * All new [NodeTypeDefinition]s must be registered here so that the editor,
 * engine and persistence layers share a single source of truth.
 *
 * Port schemas are derived from the typed data classes in
 * `domain/model/items/` via [schemaOf]. EXECUTION ports carry [ItemSchema.Unit]
 * as a placeholder (their schema is never consulted).
 */
object NodeTypeRegistry {

    private val triggers = listOf(
        NodeTypeDefinition(
            typeId = "trigger.manual",
            displayName = "Manual Trigger",
            description = "Starts the workflow when you tap run",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.MANUAL,
            ports = listOf(execOut()),
            iconKey = "bolt",
        ),
        NodeTypeDefinition(
            typeId = "trigger.schedule",
            displayName = "Schedule",
            description = "Starts the workflow on a fixed schedule",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.TIME_SCHEDULE,
            ports = listOf(execOut(), dataOut<ScheduleFire>("fireTime")),
            iconKey = "schedule",
        ),
        NodeTypeDefinition(
            typeId = "trigger.notification",
            displayName = "Notification Received",
            description = "Starts when a matching notification arrives",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.MESSAGING,
            ports = listOf(execOut(), dataOut<NotificationEvent>("notification")),
            iconKey = "notification",
        ),
        NodeTypeDefinition(
            typeId = "trigger.sms",
            displayName = "SMS Received",
            description = "Starts when an SMS arrives",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.MESSAGING,
            ports = listOf(execOut(), dataOut<SmsMessage>("sms")),
            iconKey = "sms",
        ),
        NodeTypeDefinition(
            typeId = "trigger.boot",
            displayName = "Device Boot",
            description = "Starts once after the device finishes booting",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.POWER_BATTERY,
            ports = listOf(execOut()),
            iconKey = "boot",
        ),
        NodeTypeDefinition(
            typeId = "trigger.charging",
            displayName = "Charging",
            description = "Starts when the device starts or stops charging",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.POWER_BATTERY,
            ports = listOf(execOut(), dataOut<BatteryState>("state")),
            iconKey = "battery_charging",
        ),
        NodeTypeDefinition(
            typeId = "trigger.battery_level",
            displayName = "Battery Level",
            description = "Starts when the battery level crosses a threshold (polls in the background)",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.POWER_BATTERY,
            ports = listOf(execOut(), dataOut<BatteryState>("state")),
            iconKey = "battery_level",
        ),
        NodeTypeDefinition(
            typeId = "trigger.geofence",
            displayName = "Geofence",
            description = "Starts when the device enters, exits or dwells inside a circular area",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.LOCATION,
            ports = listOf(execOut(), dataOut<GeofenceEvent>("event")),
            iconKey = "location",
        ),
        // Tier 0 — engine-internal triggers.
        NodeTypeDefinition(
            typeId = "trigger.empty",
            displayName = "Empty Trigger",
            description = "Fires immediately when the workflow starts (no external event)",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.AUTOMATION,
            ports = listOf(execOut()),
            iconKey = "bolt",
        ),
        NodeTypeDefinition(
            typeId = "trigger.app_init",
            displayName = "App Initialised",
            description = "Fires when the app is initialised and running",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.AUTOMATION,
            ports = listOf(execOut()),
            iconKey = "bolt",
        ),
        NodeTypeDefinition(
            typeId = "trigger.macro_finished",
            displayName = "Macro Finished",
            description = "Fires when a macro finishes executing after being triggered",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.AUTOMATION,
            ports = listOf(execOut()),
            iconKey = "bolt",
        ),
        NodeTypeDefinition(
            typeId = "trigger.macro_enabled",
            displayName = "Macro Enabled",
            description = "Fires when this macro is enabled (its triggers are armed)",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.AUTOMATION,
            ports = listOf(execOut()),
            iconKey = "bolt",
        ),
        NodeTypeDefinition(
            typeId = "trigger.mode_change",
            displayName = "Dark Theme Change",
            description = "Fires when the device's UI / night mode changes",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.DEVICE_STATE,
            ports = listOf(execOut(), dataOut<ModeChange>("mode")),
            iconKey = "bolt",
        ),
        NodeTypeDefinition(
            typeId = "trigger.variable_change",
            displayName = "Variable Change",
            description = "Fires when a named variable changes value",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.VARIABLES,
            ports = listOf(execOut(), dataOut<VariableChange>("variable")),
            iconKey = "bolt",
        ),
        NodeTypeDefinition(
            typeId = "trigger.stopwatch",
            displayName = "Stopwatch",
            description = "Ticks on a fixed interval, reporting elapsed time",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.TIME_SCHEDULE,
            ports = listOf(execOut(), dataOut<StopwatchTick>("tick")),
            iconKey = "timer",
        ),
        NodeTypeDefinition(
            typeId = "trigger.sleep",
            displayName = "Sleep",
            description = "Ticks repeatedly but only during a configured daily time window",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.TIME_SCHEDULE,
            ports = listOf(execOut()),
            iconKey = "timer",
        ),
        // Tier 1 — broadcast-receiver triggers.
        NodeTypeDefinition(
            typeId = "trigger.wifi_state",
            displayName = "Wi-Fi State Change",
            description = "Starts when Wi-Fi is enabled or disabled",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.CONNECTIVITY,
            ports = listOf(execOut(), dataOut<SystemState>("state")),
            iconKey = "wifi",
        ),
        NodeTypeDefinition(
            typeId = "trigger.bluetooth",
            displayName = "Bluetooth State Change",
            description = "Starts when Bluetooth is turned on or off",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.CONNECTIVITY,
            ports = listOf(execOut(), dataOut<SystemState>("state")),
            iconKey = "bluetooth",
        ),
        NodeTypeDefinition(
            typeId = "trigger.bluetooth_connect",
            displayName = "Bluetooth Device Connected",
            description = "Starts when a Bluetooth device connects or disconnects",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.CONNECTIVITY,
            ports = listOf(execOut(), dataOut<SystemState>("state")),
            iconKey = "bluetooth",
        ),
        NodeTypeDefinition(
            typeId = "trigger.airplane_mode",
            displayName = "Airplane Mode Changed",
            description = "Starts when airplane mode is toggled",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.CONNECTIVITY,
            ports = listOf(execOut(), dataOut<SystemState>("state")),
            iconKey = "bolt",
        ),
        NodeTypeDefinition(
            typeId = "trigger.call_state",
            displayName = "Incoming Call State",
            description = "Starts when the phone call state changes (ringing, offhook, idle)",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.PHONE_MEDIA,
            ports = listOf(execOut(), dataOut<SystemState>("state")),
            iconKey = "bolt",
        ),
        NodeTypeDefinition(
            typeId = "trigger.headset",
            displayName = "Headset Plugged",
            description = "Starts when a wired headset is plugged or unplugged",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.CONNECTIVITY,
            ports = listOf(execOut(), dataOut<SystemState>("state")),
            iconKey = "bolt",
        ),
        NodeTypeDefinition(
            typeId = "trigger.usb_device",
            displayName = "USB Device Connected",
            description = "Starts when a USB device is connected or disconnected",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.CONNECTIVITY,
            ports = listOf(execOut(), dataOut<SystemState>("state")),
            iconKey = "bolt",
        ),
        NodeTypeDefinition(
            typeId = "trigger.dock",
            displayName = "Device Docked",
            description = "Starts when the device is docked or undocked",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.CONNECTIVITY,
            ports = listOf(execOut(), dataOut<SystemState>("state")),
            iconKey = "bolt",
        ),
        NodeTypeDefinition(
            typeId = "trigger.screen",
            displayName = "Screen On / Off",
            description = "Starts when the screen turns on or off",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.DEVICE_STATE,
            ports = listOf(execOut(), dataOut<SystemState>("state")),
            iconKey = "bolt",
        ),
        NodeTypeDefinition(
            typeId = "trigger.user_present",
            displayName = "Device Unlocked",
            description = "Starts when the user unlocks the device",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.DEVICE_STATE,
            ports = listOf(execOut(), dataOut<SystemState>("state")),
            iconKey = "bolt",
        ),
        NodeTypeDefinition(
            typeId = "trigger.ringer_mode",
            displayName = "Ringer Mode Changed",
            description = "Starts when the ringer mode changes (normal, silent, vibrate)",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.PHONE_MEDIA,
            ports = listOf(execOut(), dataOut<SystemState>("state")),
            iconKey = "bolt",
        ),
        NodeTypeDefinition(
            typeId = "trigger.power_save",
            displayName = "Power Save Mode Changed",
            description = "Starts when power-save mode is toggled on or off",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.POWER_BATTERY,
            ports = listOf(execOut(), dataOut<SystemState>("state")),
            iconKey = "bolt",
        ),
        NodeTypeDefinition(
            typeId = "trigger.timezone_change",
            displayName = "Timezone Changed",
            description = "Starts when the device timezone changes",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.TIME_SCHEDULE,
            ports = listOf(execOut(), dataOut<SystemState>("state")),
            iconKey = "bolt",
        ),
        NodeTypeDefinition(
            typeId = "trigger.locale_change",
            displayName = "Locale Changed",
            description = "Starts when the device locale changes",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.DEVICE_STATE,
            ports = listOf(execOut(), dataOut<SystemState>("state")),
            iconKey = "bolt",
        ),
        NodeTypeDefinition(
            typeId = "trigger.date_change",
            displayName = "Date Changed",
            description = "Starts when the device date changes (midnight rollover)",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.TIME_SCHEDULE,
            ports = listOf(execOut(), dataOut<SystemState>("state")),
            iconKey = "bolt",
        ),
        NodeTypeDefinition(
            typeId = "trigger.shutdown",
            displayName = "Device Shutting Down",
            description = "Starts when the device is shutting down",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.POWER_BATTERY,
            ports = listOf(execOut(), dataOut<SystemState>("state")),
            iconKey = "bolt",
        ),
        NodeTypeDefinition(
            typeId = "trigger.time_tick",
            displayName = "Regular Time Tick",
            description = "Fires roughly every minute while the device is awake",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.TIME_SCHEDULE,
            ports = listOf(execOut(), dataOut<SystemState>("state")),
            iconKey = "timer",
        ),
        NodeTypeDefinition(
            typeId = "trigger.app_installed",
            displayName = "App Installed / Removed",
            description = "Starts when an app is installed, removed or replaced",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.AUTOMATION,
            ports = listOf(execOut(), dataOut<PackageEvent>("package")),
            iconKey = "bolt",
        ),
        NodeTypeDefinition(
            typeId = "trigger.media_button",
            displayName = "Media Button Pressed",
            description = "Starts when a media button is pressed (play, pause, next, etc.)",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.PHONE_MEDIA,
            ports = listOf(execOut(), dataOut<MediaEvent>("media")),
            iconKey = "bolt",
        ),
        NodeTypeDefinition(
            typeId = "trigger.media_mount",
            displayName = "Media Mounted / Unmounted",
            description = "Starts when external media is mounted, unmounted or ejected",
            kind = NodeKind.TRIGGER,
            category = NodeCategory.PHONE_MEDIA,
            ports = listOf(execOut(), dataOut<MediaEvent>("media")),
            iconKey = "bolt",
        ),
    )

    private val actions = listOf(
        NodeTypeDefinition(
            typeId = "action.http",
            displayName = "HTTP Request",
            description = "Calls a web API and exposes the typed response",
            kind = NodeKind.ACTION,
            category = NodeCategory.NETWORK,
            ports = listOf(execIn(), execOut(), dataOut<HttpResponseItem>("response")),
            iconKey = "http",
        ),
        NodeTypeDefinition(
            typeId = CONDITION_TYPE_ID,
            displayName = "If / Condition",
            description = "Routes execution based on a typed comparison of a field of the connected data input",
            kind = NodeKind.ACTION,
            category = NodeCategory.FLOW_CONTROL,
            ports = listOf(
                execIn(),
                execOut("true"),
                execOut("false"),
            ),
            iconKey = "split",
        ),
        NodeTypeDefinition(
            typeId = "action.notify",
            displayName = "Show Notification",
            description = "Posts a notification on this device",
            kind = NodeKind.ACTION,
            category = NodeCategory.NOTIFICATIONS,
            ports = listOf(execIn(), execOut()),
            iconKey = "send",
        ),
        NodeTypeDefinition(
            typeId = "action.delay",
            displayName = "Wait",
            description = "Pauses the workflow for a while",
            kind = NodeKind.ACTION,
            category = NodeCategory.TIMING,
            ports = listOf(execIn(), execOut()),
            iconKey = "timer",
        ),
        NodeTypeDefinition(
            typeId = "action.wifi",
            displayName = "Toggle Wi-Fi",
            description = "Turns Wi-Fi on or off and reports the resulting state",
            kind = NodeKind.ACTION,
            category = NodeCategory.DEVICE_SETTINGS,
            ports = listOf(execIn(), execOut(), dataOut<WifiState>("state")),
            iconKey = "wifi",
        ),
        NodeTypeDefinition(
            typeId = "action.volume",
            displayName = "Set Volume",
            description = "Adjusts an audio stream's volume (up, down, set, mute or unmute)",
            kind = NodeKind.ACTION,
            category = NodeCategory.DEVICE_SETTINGS,
            ports = listOf(execIn(), execOut(), dataOut<VolumeState>("state")),
            iconKey = "volume",
        ),
        NodeTypeDefinition(
            typeId = "action.dnd",
            displayName = "Do Not Disturb",
            description = "Toggles Do-Not-Disturb on or off with a chosen policy level",
            kind = NodeKind.ACTION,
            category = NodeCategory.DEVICE_SETTINGS,
            ports = listOf(execIn(), execOut(), dataOut<DndState>("state")),
            iconKey = "dnd",
        ),
    )

    /**
     * Adaptive break-struct node. `action.break` splits a struct into its
     * fields and is the only node with [hasDynamicPorts] = true: its field
     * output ports are resolved at design time from the schema of whatever is
     * connected to its `struct` input (see [effectivePorts]). The former
     * `action.make` node has been removed — per-field data inputs are now
     * exposed directly on each node via [WorkflowNode.exposedInputs].
     */
    private val structNodes: List<NodeTypeDefinition> = listOf(
        NodeTypeDefinition(
            typeId = BREAK_TYPE_ID,
            displayName = "Break Struct",
            description = "Splits a struct into its individual fields (auto-detects the struct from the input)",
            kind = NodeKind.ACTION,
            category = NodeCategory.DATA,
            ports = listOf(
                execIn(),
                execOut(),
                Port(
                    name = BREAK_STRUCT_IN,
                    kind = PortKind.DATA,
                    direction = Direction.IN,
                    schema = ItemSchema.Wildcard,
                ),
            ),
            iconKey = "split",
            hasDynamicPorts = true,
        ),
    )

    val all: List<NodeTypeDefinition> = triggers + actions + structNodes

    private val byId: Map<String, NodeTypeDefinition> = all.associateBy { it.typeId }

    fun byId(typeId: String): NodeTypeDefinition? = byId[typeId]

    fun byKind(kind: NodeKind): List<NodeTypeDefinition> = all.filter { it.kind == kind }

    fun byKindAndCategory(kind: NodeKind, category: NodeCategory): List<NodeTypeDefinition> =
        all.filter { it.kind == kind && it.category == category }

    fun categoriesFor(kind: NodeKind): List<NodeCategory> =
        NodeCategory.values().filter { it.kind == kind && byKindAndCategory(kind, it).isNotEmpty() }
}
