package io.github.m1n1m1.easymatic.domain.backup

import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.domain.model.VariableRef
import io.github.m1n1m1.easymatic.domain.model.Workflow
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.registry.API_TOKEN_KEY
import io.github.m1n1m1.easymatic.domain.registry.API_TRIGGER_TYPE_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a restore decides about each workflow and each variable value, in isolation. */
class WorkflowPlacementTest {

    private val token = "live-secret-token"

    private val incoming = Workflow(
        id = "wf-1",
        name = "Morning",
        enabled = true,
        schemaVersion = Workflow.CURRENT_SCHEMA_VERSION - 1,
        nodes = listOf(
            WorkflowNode(
                id = NodeId("n1"),
                typeId = API_TRIGGER_TYPE_ID,
                name = "api",
                x = 0f,
                y = 0f,
                config = mapOf(API_TOKEN_KEY to token),
            ),
        ),
    )

    @Test
    fun `a free id is kept, armed as recorded, with its token`() {
        val placed = placeWorkflow(incoming, existingIds = setOf("other"), newId = { "fresh" }, copyName = ::copyName)

        assertFalse(placed.isCopy)
        assertEquals("wf-1", placed.workflow.id)
        assertEquals("wf-1", placed.originalId)
        assertTrue(placed.workflow.enabled)
        assertEquals("Morning", placed.workflow.name)
        assertEquals(token, placed.workflow.nodes[0].config[API_TOKEN_KEY])
        // Restated, because the current build is about to save it.
        assertEquals(Workflow.CURRENT_SCHEMA_VERSION, placed.workflow.schemaVersion)
    }

    @Test
    fun `a colliding id becomes a disarmed copy with a re-minted token`() {
        val placed = placeWorkflow(incoming, existingIds = setOf("wf-1"), newId = { "fresh" }, copyName = ::copyName)

        assertTrue(placed.isCopy)
        assertEquals("fresh", placed.workflow.id)
        assertEquals("wf-1", placed.originalId)
        assertEquals("Morning copy", placed.workflow.name)
        assertFalse(placed.workflow.enabled)
        val reissued = placed.workflow.nodes[0].config[API_TOKEN_KEY]
        assertNotNull(reissued)
        assertNotEquals(token, reissued)
    }

    @Test
    fun `a copy's local values follow it to its new id`() {
        val old = VariableRef.scopePrefix("wf-1")
        val new = VariableRef.scopePrefix("fresh")
        val incomingValues = mapOf(
            "${old}counter" to "7",
            "${VariableRef.scopePrefix("wf-2")}other" to "1",
            VariableRef.storeKey(VariableRef.Global("g1"), "") to "file",
        )
        val local = mapOf(
            VariableRef.storeKey(VariableRef.Global("g1"), "") to "phone",
            "${VariableRef.scopePrefix("mine")}kept" to "yes",
        )

        val merged = mergeVariables(local, incomingValues, idRemap = mapOf("wf-1" to "fresh"))

        assertEquals("7", merged["${new}counter"])
        assertFalse(merged.containsKey("${old}counter"))
        assertEquals("1", merged["${VariableRef.scopePrefix("wf-2")}other"])
        // Incoming wins: the globals library was just replaced by the file's.
        assertEquals("file", merged[VariableRef.storeKey(VariableRef.Global("g1"), "")])
        // Local-only keys belong to macros the file never had, and stay.
        assertEquals("yes", merged["${VariableRef.scopePrefix("mine")}kept"])
    }

    @Test
    fun `nothing to remap leaves incoming keys as they are`() {
        val merged = mergeVariables(emptyMap(), mapOf("a" to "1"), idRemap = emptyMap())
        assertEquals(mapOf("a" to "1"), merged)
    }

    private fun copyName(name: String) = "$name copy"
}
