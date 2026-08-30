package io.github.m1n1m1.easymatic.domain.registry

import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.core.model.NodeTypeId
import io.github.m1n1m1.easymatic.domain.model.VariableDeclaration
import io.github.m1n1m1.easymatic.domain.model.VariableRef
import io.github.m1n1m1.easymatic.domain.model.Workflow
import io.github.m1n1m1.easymatic.domain.model.WorkflowNode
import io.github.m1n1m1.easymatic.domain.model.config.ValueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Opening a macro written before variables were declared.
 *
 * Its configs hold **names**; a reference is now an **id**. Getting this wrong is
 * not a cosmetic failure — a name adopted into the wrong scope severs any pair of
 * macros that shared a counter, and one adopted with the wrong type retypes ports
 * underneath edges that currently work.
 */
class VariableRepairTest {

    private val nameKey = ConfigKey("name")

    private fun legacyWorkflow(name: String) = Workflow(
        nodes = listOf(
            WorkflowNode(
                NodeId("set"), NodeTypeId("action.set_variable"), "Set", 0f, 0f,
                config = mapOf(nameKey to name, ConfigKey("value") to "3"),
            ),
        ),
    )

    @Test
    fun `a legacy name becomes a global declaration and a g-prefixed ref`() {
        // Global, not local: `action.set_variable` promised a value "readable by
        // later runs and other macros", and the store was keyed by bare name with
        // no scope, so every existing variable already *was* global.
        val result = repairVariableRefs(legacyWorkflow("counter"), globalsByName = emptyMap())

        val adopted = result.adopted.single()
        assertEquals("counter", adopted.name)
        assertEquals(ValueType.TEXT, adopted.type)
        assertEquals(
            VariableRef.globalSpec(adopted.id),
            result.workflow.nodes.single().config[nameKey],
        )
    }

    @Test
    fun `a name another workflow already adopted resolves to that same declaration`() {
        val existing = VariableDeclaration(id = "already", name = "counter")
        val result = repairVariableRefs(legacyWorkflow("counter"), mapOf(existing.name to existing.id))

        assertTrue(result.adopted.isEmpty())
        assertEquals(VariableRef.globalSpec("already"), result.workflow.nodes.single().config[nameKey])
    }

    @Test
    fun `a ref that already resolves locally is left alone`() {
        val declared = VariableDeclaration(id = "local1", name = "counter")
        val workflow = legacyWorkflow(VariableRef.localSpec(declared.id)).copy(variables = listOf(declared))

        val result = repairVariableRefs(workflow, emptyMap())

        assertTrue(result.adopted.isEmpty())
        assertEquals(workflow, result.workflow)
    }

    @Test
    fun `an already-global ref is left alone`() {
        val workflow = legacyWorkflow(VariableRef.globalSpec("g1"))
        val result = repairVariableRefs(workflow, emptyMap())

        assertTrue(result.adopted.isEmpty())
        assertEquals(workflow, result.workflow)
    }

    @Test
    fun `a node with no variable chosen is left alone`() {
        val result = repairVariableRefs(legacyWorkflow(""), emptyMap())

        assertTrue(result.adopted.isEmpty())
        assertEquals("", result.workflow.nodes.single().config[nameKey])
    }

    @Test
    fun `running it twice changes nothing the second time`() {
        // It reapplies on every load — the repaired graph is not written back — so
        // idempotence is what makes that free rather than a slow corruption.
        val first = repairVariableRefs(legacyWorkflow("counter"), emptyMap())
        val globals = first.adopted.associate { it.name to it.id }
        val second = repairVariableRefs(first.workflow, globals)

        assertTrue(second.adopted.isEmpty())
        assertEquals(first.workflow, second.workflow)
    }

    @Test
    fun `two nodes naming the same legacy variable share one declaration`() {
        val workflow = legacyWorkflow("counter").let { base ->
            base.copy(
                nodes = base.nodes + WorkflowNode(
                    NodeId("read"), NodeTypeId("value.variable"), "Read", 0f, 50f,
                    config = mapOf(nameKey to "counter"),
                ),
            )
        }

        val result = repairVariableRefs(workflow, emptyMap())

        assertEquals(1, result.adopted.size)
        assertEquals(1, result.workflow.nodes.mapNotNull { it.config[nameKey] }.toSet().size)
    }
}
