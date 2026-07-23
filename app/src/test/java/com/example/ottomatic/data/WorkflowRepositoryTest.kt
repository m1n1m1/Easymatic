package com.example.ottomatic.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Verifies [WorkflowRepository.setEnabled] persists the armed flag independently
 * of the graph and round-trips through [WorkflowRepository.load].
 */
class WorkflowRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newRepo(): WorkflowRepository = WorkflowRepository(tempFolder.newFolder())

    @Test
    fun `setEnabled true then load reports enabled true`() = runBlocking {
        val repo = newRepo()
        repo.setEnabled(true)
        val loaded = repo.load()
        assertNotNull(loaded)
        assertEquals(true, loaded!!.enabled)
    }

    @Test
    fun `setEnabled false overrides a previously enabled workflow`() = runBlocking {
        val repo = newRepo()
        repo.setEnabled(true)
        repo.setEnabled(false)
        assertEquals(false, repo.load()!!.enabled)
    }

    @Test
    fun `setEnabled preserves a previously saved graph`() = runBlocking {
        val repo = newRepo()
        val graph = com.example.ottomatic.domain.model.Workflow(
            id = "default",
            nodes = listOf(
                com.example.ottomatic.domain.model.WorkflowNode("n1", "trigger.manual", "M", 0f, 0f),
            ),
        )
        repo.save(graph)
        repo.setEnabled(true)
        val loaded = repo.load()!!
        assertEquals(true, loaded.enabled)
        assertEquals(1, loaded.nodes.size)
        assertEquals("n1", loaded.nodes.first().id)
    }
}
