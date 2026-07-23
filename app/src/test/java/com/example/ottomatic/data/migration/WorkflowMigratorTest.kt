package com.example.ottomatic.data.migration

import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.registry.NodeTypeRegistry
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies [WorkflowMigrator] lifts v1 (single `connections` list with integer
 * port indices) payloads to the v2 two-channel model with named ports.
 */
class WorkflowMigratorTest {

    private val json = Json { prettyPrint = true }

    @Test
    fun `v1 sample workflow migrates to v2 exec connections with named ports`() {
        val v1Json = """
            {
              "id":"default",
              "name":"Demo",
              "nodes":[
                {"id":"n1","typeId":"trigger.manual","name":"Manual","x":0.0,"y":0.0,"config":{}},
                {"id":"n2","typeId":"action.condition","name":"If","x":0.0,"y":100.0,"config":{}}
              ],
              "connections":[
                {"id":"c1","fromNodeId":"n1","fromPortIndex":0,"toNodeId":"n2","toPortIndex":0}
              ]
            }
        """.trimIndent()
        val migrated = WorkflowMigrator.migrate(v1Json)
        assertEquals(Workflow.CURRENT_SCHEMA_VERSION, migrated.schemaVersion)
        assertEquals(1, migrated.execConnections.size)
        assertEquals(0, migrated.dataConnections.size)
        val conn = migrated.execConnections.first()
        assertEquals("c1", conn.id)
        assertEquals("n1", conn.fromNodeId)
        assertEquals("n2", conn.toNodeId)
        // n1 trigger.manual output index 0 -> "out"; n2 action.condition input index 0 -> "in"
        assertEquals("out", conn.fromPort)
        assertEquals("in", conn.toPort)
    }

    @Test
    fun `v1 condition true false branch ports map by index`() {
        val v1Json = """
            {
              "nodes":[
                {"id":"n1","typeId":"trigger.manual","name":"Manual","x":0.0,"y":0.0,"config":{}},
                {"id":"n2","typeId":"action.condition","name":"If","x":0.0,"y":100.0,"config":{}},
                {"id":"n3","typeId":"action.notify","name":"Notify","x":0.0,"y":200.0,"config":{}}
              ],
              "connections":[
                {"id":"c1","fromNodeId":"n1","fromPortIndex":0,"toNodeId":"n2","toPortIndex":0},
                {"id":"c2","fromNodeId":"n2","fromPortIndex":1,"toNodeId":"n3","toPortIndex":0}
              ]
            }
        """.trimIndent()
        val migrated = WorkflowMigrator.migrate(v1Json)
        // condition outputPorts are [true, false]; index 1 -> "false"
        val c2 = migrated.execConnections.first { it.id == "c2" }
        assertEquals("false", c2.fromPort)
        assertEquals("in", c2.toPort)
    }

    @Test
    fun `v2 payload is decoded unchanged`() {
        val v2 = Workflow(
            id = "w",
            name = "V2",
            schemaVersion = Workflow.CURRENT_SCHEMA_VERSION,
            nodes = listOf(com.example.ottomatic.domain.model.WorkflowNode("n1", "trigger.manual", "M", 0f, 0f)),
            execConnections = listOf(
                com.example.ottomatic.domain.model.ExecConnection("c1", "n1", "out", "n2", "in"),
            ),
        )
        val encoded = json.encodeToString(Workflow.serializer(), v2)
        val decoded = WorkflowMigrator.migrate(encoded)
        assertEquals(v2.schemaVersion, decoded.schemaVersion)
        assertEquals(v2.execConnections.size, decoded.execConnections.size)
    }

    @Test
    fun `migrated port names match NodeTypeRegistry output ports`() {
        val v1Json = """
            {
              "nodes":[
                {"id":"n1","typeId":"trigger.sms","name":"SMS","x":0.0,"y":0.0,"config":{}},
                {"id":"n2","typeId":"action.http","name":"HTTP","x":0.0,"y":100.0,"config":{}}
              ],
              "connections":[
                {"id":"c1","fromNodeId":"n1","fromPortIndex":0,"toNodeId":"n2","toPortIndex":0}
              ]
            }
        """.trimIndent()
        val migrated = WorkflowMigrator.migrate(v1Json)
        val smsDef = NodeTypeRegistry.byId("trigger.sms")!!
        val httpDef = NodeTypeRegistry.byId("action.http")!!
        val conn = migrated.execConnections.first()
        assertEquals(smsDef.outputPorts[0].name, conn.fromPort)
        assertEquals(httpDef.inputPorts[0].name, conn.toPort)
    }

    @Test
    fun `empty v1 connections list migrates to empty v2`() {
        val v1Json = """{"nodes":[],"connections":[]}"""
        val migrated = WorkflowMigrator.migrate(v1Json)
        assertTrue(migrated.execConnections.isEmpty())
        assertTrue(migrated.dataConnections.isEmpty())
    }

    @Test
    fun `v2 payload without enabled field loads with enabled false`() {
        // A v2 file (schemaVersion 2) predates the `enabled` flag. It must load
        // without error and default `enabled` to false (macros disabled until the
        // user opts in), and re-save at the current schema version.
        val v2Json = """
            {
              "id":"default",
              "name":"Legacy v2",
              "schemaVersion":2,
              "nodes":[
                {"id":"n1","typeId":"trigger.manual","name":"Manual","x":0.0,"y":0.0,"config":{}}
              ],
              "execConnections":[],
              "dataConnections":[]
            }
        """.trimIndent()
        val migrated = WorkflowMigrator.migrate(v2Json)
        assertEquals(false, migrated.enabled)
        assertEquals("default", migrated.id)
        assertEquals(1, migrated.nodes.size)
    }

    @Test
    fun `current-version payload with enabled true round trips`() {
        val workflow = Workflow(
            id = "w",
            enabled = true,
            nodes = listOf(com.example.ottomatic.domain.model.WorkflowNode("n1", "trigger.manual", "M", 0f, 0f)),
        )
        val encoded = json.encodeToString(Workflow.serializer(), workflow)
        val decoded = WorkflowMigrator.migrate(encoded)
        assertEquals(true, decoded.enabled)
        assertEquals(Workflow.CURRENT_SCHEMA_VERSION, decoded.schemaVersion)
    }
}
