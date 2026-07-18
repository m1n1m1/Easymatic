package com.example.ottomatic.data.migration

import com.example.ottomatic.domain.model.ExecConnection
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.registry.NodeTypeRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Migrates persisted [Workflow] JSON from older schema versions to the current
 * two-channel (execution + data) model.
 *
 * v1 → v2: the single `connections: List<Connection>` (with integer port
 * indices) becomes `execConnections: List<ExecConnection>` (with named ports).
 * All v1 connections were control-flow edges, so they all become exec edges.
 * Port indices are resolved to names via [NodeTypeRegistry]. The resulting
 * `dataConnections` list is empty; users add data edges post-migration.
 */
object WorkflowMigrator {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Detects and migrates the [rawJson] workflow payload. If it is already at
     * the current schema version (or newer) it is decoded as-is. If it is a v1
     * payload (has a `connections` array and no `execConnections`), it is
     * migrated to v2.
     */
    @Suppress("ReturnCount")
    fun migrate(rawJson: String): Workflow {
        val root = json.parseToJsonElement(rawJson)
        if (root !is JsonObject) return Workflow()
        val version = root["schemaVersion"]?.jsonPrimitive?.intOrNull
        if (version != null && version >= Workflow.CURRENT_SCHEMA_VERSION) {
            return json.decodeFromString(Workflow.serializer(), rawJson)
        }
        if (root["connections"] == null || root["connections"]!!.jsonArray.isEmpty()) {
            return json.decodeFromString(Workflow.serializer(), rawJson)
        }
        return migrateV1ToV2(root)
    }

    private fun migrateV1ToV2(root: JsonObject): Workflow {
        val v1 = json.decodeFromString(WorkflowV1.serializer(), root.toString())
        val migratedConnections = v1.connections.mapNotNull { conn ->
            val fromNode = v1.nodes.firstOrNull { it.id == conn.fromNodeId } ?: return@mapNotNull null
            val toNode = v1.nodes.firstOrNull { it.id == conn.toNodeId } ?: return@mapNotNull null
            val fromDef = NodeTypeRegistry.byId(fromNode.typeId) ?: return@mapNotNull null
            val toDef = NodeTypeRegistry.byId(toNode.typeId) ?: return@mapNotNull null
            val fromPort = fromDef.outputPorts.getOrNull(conn.fromPortIndex)?.name ?: return@mapNotNull null
            val toPort = toDef.inputPorts.getOrNull(conn.toPortIndex)?.name ?: return@mapNotNull null
            ExecConnection(
                id = conn.id,
                fromNodeId = conn.fromNodeId,
                fromPort = fromPort,
                toNodeId = conn.toNodeId,
                toPort = toPort,
            )
        }
        return Workflow(
            id = v1.id,
            name = v1.name,
            schemaVersion = Workflow.CURRENT_SCHEMA_VERSION,
            nodes = v1.nodes,
            execConnections = migratedConnections,
            dataConnections = emptyList(),
        )
    }

    @Serializable
    private data class WorkflowV1(
        val id: String = "default",
        val name: String = "My Workflow",
        val nodes: List<WorkflowNode> = emptyList(),
        val connections: List<ConnectionV1> = emptyList(),
    )

    @Serializable
    private data class ConnectionV1(
        val id: String,
        val fromNodeId: String,
        val fromPortIndex: Int,
        val toNodeId: String,
        val toPortIndex: Int,
    )

    @Suppress("unused")
    private fun JsonObject.getLong(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
}
