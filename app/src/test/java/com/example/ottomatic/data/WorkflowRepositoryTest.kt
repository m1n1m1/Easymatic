package com.example.ottomatic.data

import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.ExecConnection
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.WorkflowSummary
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Verifies the id-scoped multi-workflow persistence: create/list/load/delete and
 * per-workflow [WorkflowRepository.setEnabled].
 */
class WorkflowRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var folder: File

    private fun newRepo(): WorkflowRepository =
        WorkflowRepository(tempFolder.newFolder().also { folder = it })

    @Test
    fun `list is empty for a fresh repository`() = runBlocking {
        assertEquals(emptyList<WorkflowSummary>(), newRepo().list())
    }

    @Test
    fun `create persists a workflow that load returns`() = runBlocking {
        val repo = newRepo()
        val workflow = repo.create("My Workflow")
        assertEquals("My Workflow", workflow.name)
        val loaded = repo.load(workflow.id)
        assertNotNull(loaded)
        assertEquals(workflow.id, loaded!!.id)
        assertEquals("My Workflow", loaded.name)
    }

    @Test
    fun `list returns all created summaries sorted by name`() = runBlocking {
        val repo = newRepo()
        repo.create("Beta")
        repo.create("Alpha")
        repo.create("Gamma")
        val names = repo.list().map { it.name }
        assertEquals(listOf("Alpha", "Beta", "Gamma"), names)
    }

    @Test
    fun `delete removes a workflow`() = runBlocking {
        val repo = newRepo()
        val workflow = repo.create("To Delete")
        repo.delete(workflow.id)
        assertNull(repo.load(workflow.id))
        assertTrue(repo.list().isEmpty())
    }

    @Test
    fun `rename updates only the name`() = runBlocking {
        val repo = newRepo()
        val workflow = repo.create("Old")
        repo.rename(workflow.id, "New")
        val loaded = repo.load(workflow.id)!!
        assertEquals("New", loaded.name)
        assertEquals(workflow.id, loaded.id)
    }

    @Test
    fun `setEnabled true then list reports enabled true`() = runBlocking {
        val repo = newRepo()
        val workflow = repo.create("Armed")
        repo.setEnabled(workflow.id, true)
        val summary = repo.list().first { it.id == workflow.id }
        assertEquals(true, summary.enabled)
        assertEquals(true, repo.load(workflow.id)!!.enabled)
    }

    @Test
    fun `setEnabled false overrides a previously enabled workflow`() = runBlocking {
        val repo = newRepo()
        val workflow = repo.create("Armed")
        repo.setEnabled(workflow.id, true)
        repo.setEnabled(workflow.id, false)
        assertEquals(false, repo.load(workflow.id)!!.enabled)
    }

    @Test
    fun `setEnabled preserves a previously saved graph`() = runBlocking {
        val repo = newRepo()
        val workflow = repo.create("Graph")
        val graph = Workflow(
            id = workflow.id,
            name = "Graph",
            nodes = listOf(WorkflowNode(NodeId("n1"), NodeTypeId("trigger.manual"), "M", 0f, 0f)),
        )
        repo.save(graph)
        repo.setEnabled(workflow.id, true)
        val loaded = repo.load(workflow.id)!!
        assertEquals(true, loaded.enabled)
        assertEquals(1, loaded.nodes.size)
        assertEquals(NodeId("n1"), loaded.nodes.first().id)
    }

    /**
     * The graph editor writes the enabled flag as part of a full [save] rather
     * than via [WorkflowRepository.setEnabled], so that toggling also flushes
     * unsaved graph edits instead of re-reading a stale file over them.
     */
    @Test
    fun `save carries the enabled flag together with the graph`() = runBlocking {
        val repo = newRepo()
        val workflow = repo.create("Armed graph")
        repo.save(
            Workflow(
                id = workflow.id,
                name = "Armed graph",
                nodes = listOf(WorkflowNode(NodeId("n1"), NodeTypeId("trigger.manual"), "M", 0f, 0f)),
                enabled = true,
            ),
        )
        val loaded = repo.load(workflow.id)!!
        assertEquals(true, loaded.enabled)
        assertEquals(1, loaded.nodes.size)
        assertEquals(true, repo.list().first { it.id == workflow.id }.enabled)
    }

    /**
     * The fix for a macro that could not be repaired by hand: an unknown node is
     * never drawn, so it could be reported as a problem but never selected or
     * deleted. See `pruneUnknownNodes`.
     */
    @Test
    fun `load drops a node whose type this build no longer declares`() = runBlocking {
        val repo = newRepo()
        val workflow = repo.create("Stale")
        repo.save(
            workflow.copy(
                nodes = listOf(
                    WorkflowNode(NodeId("n1"), NodeTypeId("trigger.manual"), "M", 0f, 0f),
                    WorkflowNode(NodeId("ghost"), NodeTypeId("action.retired"), "G", 0f, 0f),
                ),
                execConnections = listOf(
                    ExecConnection("e1", NodeId("n1"), PortName("out"), NodeId("ghost"), PortName("in")),
                ),
            ),
        )
        val loaded = repo.load(workflow.id)!!
        assertEquals(listOf(NodeId("n1")), loaded.nodes.map { it.id })
        assertTrue(loaded.execConnections.isEmpty())
    }

    /** Nothing is written back, so the file still holds what a later build could read. */
    @Test
    fun `dropping an unknown node does not rewrite the file`() = runBlocking {
        val repo = newRepo()
        val workflow = repo.create("Stale")
        repo.save(
            workflow.copy(nodes = listOf(WorkflowNode(NodeId("ghost"), NodeTypeId("action.retired"), "G", 0f, 0f))),
        )
        repo.load(workflow.id)
        assertTrue(File(folder, "workflows/${workflow.id}.json").readText().contains("action.retired"))
    }

    /**
     * The one ordering constraint among the load-time repairs: `action.ai_agent` is a
     * retired typeId nothing declares, and `repairAiRefs` carries it forward rather
     * than letting it strand a macro — so the prune must not get to it first.
     */
    @Test
    fun `a retired AI agent node is rewritten rather than dropped`() = runBlocking {
        val repo = newRepo()
        val workflow = repo.create("Agent")
        repo.save(
            workflow.copy(nodes = listOf(WorkflowNode(NodeId("n1"), NodeTypeId("action.ai_agent"), "A", 0f, 0f))),
        )
        val loaded = repo.load(workflow.id)!!
        assertEquals(listOf(NodeTypeId("action.ai_prompt")), loaded.nodes.map { it.typeId })
    }

    @Test
    fun `enabled states are independent per workflow`() = runBlocking {
        val repo = newRepo()
        val a = repo.create("A")
        val b = repo.create("B")
        repo.setEnabled(a.id, true)
        repo.setEnabled(b.id, false)
        val summaries = repo.list().associateBy { it.id }
        assertEquals(true, summaries[a.id]!!.enabled)
        assertEquals(false, summaries[b.id]!!.enabled)
    }
}
