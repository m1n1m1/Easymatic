package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.AttachedCondition
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.ValueSource
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.ComparisonOperator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the contract that makes one value declaration serve all three of its uses:
 * a data source on the canvas, a drag-to-create suggestion, and the source of an
 * attached gate. If any view goes missing, part of the feature silently disappears.
 */
class ValueRegistryTest {

    @Test
    fun `every value is a VALUE-kind node type`() {
        for (value in ValueRegistry.all()) {
            val definition = NodeTypeRegistry.byId(value.typeId)
            assertNotNull("${value.typeId} is missing from NodeTypeRegistry", definition)
            assertEquals(NodeKind.VALUE, definition!!.kind)
            assertEquals(NodeKind.VALUE, definition.category.kind)
        }
    }

    @Test
    fun `every value is offered as a gate source on any node`() {
        val host = WorkflowNode(NodeId("host"), NodeTypeId("action.notify"), "Notify", 0f, 0f)
        val workflow = Workflow(nodes = listOf(host))
        val options = sourceOptions(workflow, host, attached = true).map { it.value }

        for (value in ValueRegistry.all()) {
            assertTrue(
                "${value.typeId} cannot be picked as a gate source",
                options.contains(ValueSource.valueSpec(value.typeId)),
            )
        }
    }

    @Test
    fun `an attached gate also offers the host's own data inputs`() {
        // `action.http` declares @Wired url/body/headers, so a gate on it should offer
        // those ports alongside the values.
        val host = WorkflowNode(NodeId("host"), NodeTypeId("action.http"), "HTTP", 0f, 0f)
        val workflow = Workflow(nodes = listOf(host))
        val schema = effectiveConditionSchema(workflow, host, AttachedCondition())

        val source = schema!!.fields.single { it.key == IF_SOURCE_KEY }
        val options = (source.type as ConfigFieldType.ENUM).options.map { it.value }
        assertTrue("expected host data inputs, got $options", options.contains(ValueSource.hostSpec(PortName("url"))))
        // Pinning a primitive type is meaningless with no port of its own to retype.
        assertNull(schema.fields.firstOrNull { it.key == IF_TYPE_CONFIG_KEY })
    }

    /**
     * The payoff of the merge: because the gate's source carries a declared schema,
     * the form narrows itself. `value.battery` is an Int, so the gate offers ordering
     * operators and an INT literal without anything being wired.
     */
    @Test
    fun `a value source types the gate's operators and literal`() {
        val host = WorkflowNode(NodeId("host"), NodeTypeId("action.notify"), "Notify", 0f, 0f)
        val workflow = Workflow(nodes = listOf(host))
        val gate = AttachedCondition(
            config = mapOf(IF_SOURCE_KEY to ValueSource.valueSpec(NodeTypeId("value.battery"))),
        )
        val schema = effectiveConditionSchema(workflow, host, gate)!!

        val operators = (schema.fields.single { it.key == IF_OPERATOR_KEY }.type as ConfigFieldType.ENUM)
            .options.map { it.value }
        assertTrue(
            "a numeric source must offer ordering, got $operators",
            operators.contains(ComparisonOperator.GREATER_THAN.name),
        )
        assertEquals(
            ConfigFieldType.INT,
            schema.fields.single { it.key == ConfigKey(IF_VALUE_IN.value) }.type,
        )
    }

    /** A boolean source gets equality only, and a BOOL literal editor. */
    @Test
    fun `a boolean value source narrows to equality`() {
        val host = WorkflowNode(NodeId("host"), NodeTypeId("action.notify"), "Notify", 0f, 0f)
        val workflow = Workflow(nodes = listOf(host))
        val gate = AttachedCondition(
            config = mapOf(IF_SOURCE_KEY to ValueSource.valueSpec(NodeTypeId("value.wifi"))),
        )
        val schema = effectiveConditionSchema(workflow, host, gate)!!

        val operators = (schema.fields.single { it.key == IF_OPERATOR_KEY }.type as ConfigFieldType.ENUM)
            .options.map { it.value }
        assertEquals(
            listOf(ComparisonOperator.EQUALS.name, ComparisonOperator.NOT_EQUALS.name),
            operators,
        )
        assertEquals(
            ConfigFieldType.BOOL,
            schema.fields.single { it.key == ConfigKey(IF_VALUE_IN.value) }.type,
        )
    }
}
