package com.example.ottomatic.domain.registry

import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.NodeTypeDefinition
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.items.BatteryState
import com.example.ottomatic.domain.model.items.HttpResponseItem
import com.example.ottomatic.domain.model.items.NotificationEvent
import com.example.ottomatic.domain.model.items.ScheduleFire
import com.example.ottomatic.domain.model.items.SmsMessage
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
            ports = listOf(execOut()),
            iconKey = "bolt",
        ),
        NodeTypeDefinition(
            typeId = "trigger.schedule",
            displayName = "Schedule",
            description = "Starts the workflow on a fixed schedule",
            kind = NodeKind.TRIGGER,
            ports = listOf(execOut(), dataOut<ScheduleFire>("fireTime")),
            iconKey = "schedule",
        ),
        NodeTypeDefinition(
            typeId = "trigger.notification",
            displayName = "Notification Received",
            description = "Starts when a matching notification arrives",
            kind = NodeKind.TRIGGER,
            ports = listOf(execOut(), dataOut<NotificationEvent>("notification")),
            iconKey = "notification",
        ),
        NodeTypeDefinition(
            typeId = "trigger.sms",
            displayName = "SMS Received",
            description = "Starts when an SMS arrives",
            kind = NodeKind.TRIGGER,
            ports = listOf(execOut(), dataOut<SmsMessage>("sms")),
            iconKey = "sms",
        ),
        NodeTypeDefinition(
            typeId = "trigger.boot",
            displayName = "Device Boot",
            description = "Starts once after the device finishes booting",
            kind = NodeKind.TRIGGER,
            ports = listOf(execOut()),
            iconKey = "boot",
        ),
        NodeTypeDefinition(
            typeId = "trigger.charging",
            displayName = "Charging",
            description = "Starts when the device starts or stops charging",
            kind = NodeKind.TRIGGER,
            ports = listOf(execOut(), dataOut<BatteryState>("state")),
            iconKey = "battery_charging",
        ),
        NodeTypeDefinition(
            typeId = "trigger.battery_level",
            displayName = "Battery Level",
            description = "Starts when the battery level crosses a threshold (polls in the background)",
            kind = NodeKind.TRIGGER,
            ports = listOf(execOut(), dataOut<BatteryState>("state")),
            iconKey = "battery_level",
        ),
    )

    private val actions = listOf(
        NodeTypeDefinition(
            typeId = "action.http",
            displayName = "HTTP Request",
            description = "Calls a web API and exposes the typed response",
            kind = NodeKind.ACTION,
            ports = listOf(execIn(), execOut(), dataOut<HttpResponseItem>("response")),
            iconKey = "http",
        ),
        NodeTypeDefinition(
            typeId = "action.condition",
            displayName = "If / Condition",
            description = "Routes execution based on a comparison",
            kind = NodeKind.ACTION,
            ports = listOf(execIn(), execOut("true"), execOut("false")),
            iconKey = "split",
        ),
        NodeTypeDefinition(
            typeId = "action.notify",
            displayName = "Show Notification",
            description = "Posts a notification on this device",
            kind = NodeKind.ACTION,
            ports = listOf(execIn(), execOut()),
            iconKey = "send",
        ),
        NodeTypeDefinition(
            typeId = "action.delay",
            displayName = "Wait",
            description = "Pauses the workflow for a while",
            kind = NodeKind.ACTION,
            ports = listOf(execIn(), execOut()),
            iconKey = "timer",
        ),
        NodeTypeDefinition(
            typeId = "action.wifi",
            displayName = "Toggle Wi-Fi",
            description = "Turns Wi-Fi on or off and reports the resulting state",
            kind = NodeKind.ACTION,
            ports = listOf(execIn(), execOut(), dataOut<WifiState>("state")),
            iconKey = "wifi",
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
}
