package com.example.ottomatic.domain.registry

import com.example.ottomatic.domain.model.Cardinality
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.NodeTypeDefinition
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.items.HttpResponseItem
import com.example.ottomatic.domain.model.items.NotificationEvent
import com.example.ottomatic.domain.model.items.ScheduleFire
import com.example.ottomatic.domain.model.items.SmsMessage
import com.example.ottomatic.domain.model.items.WifiState
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
    cardinality: Cardinality = Cardinality.ONE,
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

    val all: List<NodeTypeDefinition> = triggers + actions

    private val byId: Map<String, NodeTypeDefinition> = all.associateBy { it.typeId }

    fun byId(typeId: String): NodeTypeDefinition? = byId[typeId]

    fun byKind(kind: NodeKind): List<NodeTypeDefinition> = all.filter { it.kind == kind }
}
