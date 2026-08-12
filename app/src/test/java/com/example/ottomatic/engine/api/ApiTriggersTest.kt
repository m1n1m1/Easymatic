package com.example.ottomatic.engine.api

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.domain.model.PortSpec
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.registry.API_INPUTS_KEY
import com.example.ottomatic.domain.registry.API_LABEL_KEY
import com.example.ottomatic.domain.registry.API_TOKEN_KEY
import com.example.ottomatic.domain.registry.API_TRIGGER_TYPE_ID
import com.example.ottomatic.engine.trigger.ManualTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiTriggersTest {

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
        // why — hiding it would make a macro the user can see in Ottomatic simply
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
}
