package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.AttachedCondition
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the dual-placement contract: registering a condition once must light up
 * *both* the canvas placement (a node type in the palette, executable through
 * [ActionRegistry]) and the attached placement (a form the config sheet can
 * render). If either view goes missing, half the feature silently disappears.
 */
class ConditionRegistryTest {

    @Test
    fun `every condition is a CONDITION-kind node type`() {
        for (condition in ConditionRegistry.all()) {
            val definition = NodeTypeRegistry.byId(condition.typeId)
            assertNotNull("${condition.typeId} is missing from NodeTypeRegistry", definition)
            assertEquals(NodeKind.CONDITION, definition!!.kind)
            assertEquals(NodeKind.CONDITION, definition.category.kind)
        }
    }

    @Test
    fun `every condition can be attached and rendered as a form`() {
        val host = WorkflowNode(NodeId("host"), NodeTypeId("action.notify"), "Notify", 0f, 0f)
        val workflow = Workflow(nodes = listOf(host))
        for (condition in ConditionRegistry.all()) {
            val attached = AttachedCondition(typeId = condition.typeId)
            // Every v1 condition is configurable, so a null schema here means the
            // attached form would render as an empty box the user cannot use.
            assertNotNull(
                "${condition.typeId} has no attachable config form",
                effectiveConditionSchema(workflow, host, attached),
            )
        }
    }

    @Test
    fun `an attached compare offers the host's data inputs as its source`() {
        // `action.http` declares @Wired url/body/headers, so an attached compare
        // on it should offer those ports rather than a free-text source.
        val host = WorkflowNode(NodeId("host"), NodeTypeId("action.http"), "HTTP", 0f, 0f)
        val workflow = Workflow(nodes = listOf(host))
        val schema = effectiveConditionSchema(workflow, host, AttachedCondition(CONDITION_TYPE_ID))

        val source = schema!!.fields.single { it.key == ConfigKey(CONDITION_SOURCE_IN.value) }
        val options = (source.type as ConfigFieldType.ENUM).options.map { it.value }
        assertTrue("expected host data inputs, got $options", options.contains("url"))
        // Pinning a primitive type is meaningless with no port of its own to retype.
        assertNull(schema.fields.firstOrNull { it.key == CONDITION_TYPE_CONFIG_KEY })
    }

    @Test
    fun `a condition with no data inputs keeps its declared form when attached`() {
        val host = WorkflowNode(NodeId("host"), NodeTypeId("action.notify"), "Notify", 0f, 0f)
        val workflow = Workflow(nodes = listOf(host))
        val schema = effectiveConditionSchema(
            workflow,
            host,
            AttachedCondition(NodeTypeId("condition.battery")),
        )

        assertEquals(
            listOf(ConfigKey("operator"), ConfigKey("level")),
            schema!!.fields.map { it.key },
        )
    }
}
