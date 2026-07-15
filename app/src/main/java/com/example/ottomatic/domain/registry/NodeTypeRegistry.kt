package com.example.ottomatic.domain.registry

import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.NodeTypeDefinition

/**
 * Central registry for all available node types (triggers and actions).
 *
 * All new [NodeTypeDefinition]s must be registered here so that the editor,
 * engine and persistence layers share a single source of truth.
 */
object NodeTypeRegistry {

    private val triggers = listOf(
        NodeTypeDefinition(
            typeId = "trigger.manual",
            displayName = "Manual Trigger",
            description = "Starts the workflow when you tap run",
            kind = NodeKind.TRIGGER,
            inputPorts = emptyList(),
            outputPorts = listOf("out"),
            iconKey = "bolt",
        ),
        NodeTypeDefinition(
            typeId = "trigger.schedule",
            displayName = "Schedule",
            description = "Starts the workflow on a fixed schedule",
            kind = NodeKind.TRIGGER,
            inputPorts = emptyList(),
            outputPorts = listOf("out"),
            iconKey = "schedule",
        ),
        NodeTypeDefinition(
            typeId = "trigger.notification",
            displayName = "Notification Received",
            description = "Starts when a matching notification arrives",
            kind = NodeKind.TRIGGER,
            inputPorts = emptyList(),
            outputPorts = listOf("out"),
            iconKey = "notification",
        ),
    )

    private val actions = listOf(
        NodeTypeDefinition(
            typeId = "action.http",
            displayName = "HTTP Request",
            description = "Calls a web API",
            kind = NodeKind.ACTION,
            inputPorts = listOf("in"),
            outputPorts = listOf("out"),
            iconKey = "http",
        ),
        NodeTypeDefinition(
            typeId = "action.condition",
            displayName = "If / Condition",
            description = "Routes items based on a condition",
            kind = NodeKind.ACTION,
            inputPorts = listOf("in"),
            outputPorts = listOf("true", "false"),
            iconKey = "split",
        ),
        NodeTypeDefinition(
            typeId = "action.notify",
            displayName = "Show Notification",
            description = "Posts a notification on this device",
            kind = NodeKind.ACTION,
            inputPorts = listOf("in"),
            outputPorts = listOf("out"),
            iconKey = "send",
        ),
        NodeTypeDefinition(
            typeId = "action.delay",
            displayName = "Wait",
            description = "Pauses the workflow for a while",
            kind = NodeKind.ACTION,
            inputPorts = listOf("in"),
            outputPorts = listOf("out"),
            iconKey = "timer",
        ),
        NodeTypeDefinition(
            typeId = "action.wifi",
            displayName = "Toggle Wi-Fi",
            description = "Turns Wi-Fi on or off",
            kind = NodeKind.ACTION,
            inputPorts = listOf("in"),
            outputPorts = listOf("out"),
            iconKey = "wifi",
        ),
    )

    val all: List<NodeTypeDefinition> = triggers + actions

    private val byId: Map<String, NodeTypeDefinition> = all.associateBy { it.typeId }

    fun byId(typeId: String): NodeTypeDefinition? = byId[typeId]

    fun byKind(kind: NodeKind): List<NodeTypeDefinition> = all.filter { it.kind == kind }
}
