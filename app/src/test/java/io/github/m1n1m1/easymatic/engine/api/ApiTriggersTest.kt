package io.github.m1n1m1.easymatic.engine.api

import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.domain.model.PortSpec
import io.github.m1n1m1.easymatic.domain.model.Workflow
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.registry.API_INPUTS_KEY
import io.github.m1n1m1.easymatic.domain.registry.API_LABEL_KEY
import io.github.m1n1m1.easymatic.domain.registry.API_TOKEN_KEY
import io.github.m1n1m1.easymatic.domain.registry.API_TRIGGER_TYPE_ID
import io.github.m1n1m1.easymatic.engine.trigger.ManualTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking
import io.github.m1n1m1.easymatic.data.WorkflowRepository

class ApiTriggersTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun apiNode(
        id: String = "n1",
        name: String = "Called by Another App",
        config: Map<ConfigKey, String> = emptyMap(),
    ) = WorkflowNode(id = NodeId(id), typeId = API_TRIGGER_TYPE_ID, name = name, x = 0f, y = 0f, config = config)

    private fun workflow(vararg nodes: WorkflowNode, name: String = "My macro", enabled: Boolean = true) =
        Workflow(id = "w1", name = name, nodes = nodes.toList(), enabled = enabled)

    @Test
    fun `a macro with no API trigger contributes nothing`() {
        val manual = WorkflowNode(NodeId("m"), ManualTrigger.TYPE_ID, "Manual Trigger", 0f, 0f)
        assertTrue(apiTriggersIn(workflow(manual)).isEmpty())
    }

    @Test
    fun `a target carries its key and its declared ports`() {
        val target = apiTriggersIn(
            workflow(
                apiNode(
                    config = mapOf(
                        API_TOKEN_KEY to "abc123",
                        API_INPUTS_KEY to "city:TEXT\ncount:WHOLE_NUMBER\ntags:TEXT[]",
                    ),
                ),
            ),
        ).single()
        assertEquals("abc123", target.token)
        assertEquals(listOf("city", "count", "tags"), target.specs.map { it.name })
        assertEquals(listOf(false, false, true), target.specs.map { it.list })
    }

    @Test
    fun `an untyped port keeps ANY rather than inventing a type`() {
        val target = apiTriggersIn(workflow(apiNode(config = mapOf(API_INPUTS_KEY to "blob:ANY")))).single()
        assertEquals(null, target.specs.single().type)
        assertEquals(PortSpec.ANY, target.specs.single().type?.name ?: PortSpec.ANY)
    }

    // The label fallback chain, which is what a calling app's picker shows.

    @Test
    fun `the label prefers the trigger's own name`() {
        val target = apiTriggersIn(
            workflow(apiNode(name = "Node name", config = mapOf(API_LABEL_KEY to "Start the coffee"))),
        ).single()
        assertEquals("Start the coffee", target.label)
    }

    @Test
    fun `an unnamed trigger falls back to the node's name`() {
        assertEquals("Node name", apiTriggersIn(workflow(apiNode(name = "Node name"))).single().label)
    }

    /**
     * The palette default is skipped: a picker listing three entries all called
     * "Called by Another App" is worse than one listing the macro's name three
     * times, which at least says which macro.
     */
    @Test
    fun `a node still on its palette name falls back to the macro's`() {
        assertEquals("My macro", apiTriggersIn(workflow(apiNode(), name = "My macro")).single().label)
    }

    @Test
    fun `a disabled macro still contributes its triggers`() {
        // Listed rather than omitted, so a caller's picker can grey it out and say
        // why — hiding it would make a macro the user can see in Easymatic simply
        // missing from the list.
        val targets = apiTriggersIn(workflow(apiNode(), enabled = false))
        assertEquals(1, targets.size)
        assertEquals(false, targets.single().workflow.enabled)
    }

    @Test
    fun `two triggers in one macro are both listed, in placement order`() {
        val targets = apiTriggersIn(workflow(apiNode(id = "a"), apiNode(id = "b")))
        assertEquals(listOf("a", "b"), targets.map { it.node.id.value })
    }

    @Test
    fun `a blank inputs config declares no ports`() {
        val target = apiTriggersIn(workflow(apiNode(config = mapOf(API_INPUTS_KEY to "")))).single()
        assertTrue("a trigger that carries no data is ordinary, unlike a script with no result", target.specs.isEmpty())
    }

    @Test
    fun `token alone selects its trigger among several macros and nodes`() {
        val first = workflow(apiNode(config = mapOf(API_TOKEN_KEY to "first")))
        val second = workflow(
            apiNode(id = "a", config = mapOf(API_TOKEN_KEY to "second")),
            apiNode(id = "b", config = mapOf(API_TOKEN_KEY to "third")),
        ).copy(id = "w2")
        val targets = listOf(first, second).flatMap(::apiTriggersIn)
        assertEquals("b", resolveApiTrigger(targets, token = "third")?.node?.id?.value)
        assertEquals(null, resolveApiTrigger(targets, token = "wrong"))
        assertEquals(null, resolveApiTrigger(targets, token = ""))
        assertEquals(null, resolveApiTrigger(targets))
    }

    @Test
    fun `public IDs distinguish copied nodes without a macro ID`() {
        val original = workflow(apiNode())
        val targets = listOf(original, original.copy(id = "copy")).flatMap(::apiTriggersIn)
        for (target in targets) {
            assertEquals(target, resolveApiTrigger(targets, nodeId = target.callId))
        }
        assertEquals(null, resolveApiTrigger(targets, nodeId = "n1"))
        assertEquals(targets.first(), resolveApiTrigger(targets, macroId = "w1", nodeId = "n1"))
    }

    @Test
    fun `duplicate tokens are refused even if one macro is disabled`() {
        val original = workflow(apiNode(config = mapOf(API_TOKEN_KEY to "same")))
        val targets = listOf(original, original.copy(id = "copy", enabled = false)).flatMap(::apiTriggersIn)
        assertEquals(null, resolveApiTrigger(targets, token = "same"))
    }

    @Test
    fun `conflicting selectors never run a different target`() {
        val targets = apiTriggersIn(workflow(
            apiNode(id = "a", config = mapOf(API_TOKEN_KEY to "first")),
            apiNode(id = "b", config = mapOf(API_TOKEN_KEY to "second")),
        ))
        assertEquals(null, resolveApiTrigger(targets, nodeId = targets.first().callId, token = "second"))
        assertEquals(null, resolveApiTrigger(targets, macroId = "wrong", token = "first"))
        assertEquals(null, resolveApiTrigger(targets, macroId = "w1"))
    }

    @Test
    fun `legacy macro only calls and unique raw node IDs still resolve`() {
        val target = apiTriggersIn(workflow(apiNode())).single()
        assertEquals(target, resolveApiTrigger(listOf(target), macroId = "w1"))
        assertEquals(target, resolveApiTrigger(listOf(target), nodeId = "n1"))
        assertEquals(null, resolveApiTrigger(listOf(target), token = ""))
    }

    @Test
    fun `listed IDs and tokens resolve from storage and regeneration revokes old token`() = runBlocking {
        val repository = WorkflowRepository(folder.newFolder())
        val original = workflow(apiNode(config = mapOf(API_TOKEN_KEY to "original")))
        val copy = original.copy(id = "copy", nodes = listOf(apiNode(config = mapOf(API_TOKEN_KEY to "copy"))))
        repository.save(original)
        repository.save(copy)
        val listed = listApiTriggers(repository)
        assertEquals(2, listed.triggers.map { it.nodeId }.distinct().size)
        for (trigger in listed.triggers) {
            assertEquals(trigger.macroId, findApiTrigger(repository, null, trigger.nodeId)?.workflow?.id)
            assertEquals(trigger.macroId, findApiTrigger(repository, null, null, trigger.token)?.workflow?.id)
        }
        repository.save(original.copy(nodes = listOf(apiNode(config = mapOf(API_TOKEN_KEY to "new")))))
        assertEquals(null, findApiTrigger(repository, null, null, "original"))
        assertEquals("w1", findApiTrigger(repository, null, null, "new")?.workflow?.id)
    }
}
