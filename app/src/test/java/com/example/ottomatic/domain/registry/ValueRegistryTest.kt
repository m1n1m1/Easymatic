package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
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
 * Guards the contract that makes one value declaration serve both of its uses: a data
 * source on the canvas (including as a drag-to-create suggestion), and a source
 * `action.if` can compare with no edge drawn to it. If either view goes missing, part
 * of the feature silently disappears.
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
    fun `every value is offered as a comparison source`() {
        val options = sourceFieldOptions(ifNode())

        for (value in ValueRegistry.all()) {
            assertTrue(
                "${value.typeId} cannot be picked as a comparison source",
                options.contains(ValueSource.valueSpec(value.typeId)),
            )
        }
    }

    /**
     * The wired port is the other source, and the only one for which pinning a
     * primitive type makes sense — a value node already knows its own type, so there
     * is nothing to pin.
     */
    @Test
    fun `a comparison also offers its own wired port, and only then a type pin`() {
        val wired = ifNode()
        assertTrue(sourceFieldOptions(wired).contains(ValueSource.WIRED_SPEC))
        assertNotNull(schemaOf(wired).fields.firstOrNull { it.key == IF_TYPE_CONFIG_KEY })

        val fromValue = ifNode(ValueSource.valueSpec(NodeTypeId("value.battery")))
        assertNull(schemaOf(fromValue).fields.firstOrNull { it.key == IF_TYPE_CONFIG_KEY })
    }

    /**
     * The payoff of the merge: because a value source carries a declared schema, the
     * form narrows itself. `value.battery` is an Int, so the comparison offers ordering
     * operators and an INT literal without anything being wired.
     */
    @Test
    fun `a value source types the operators and the literal`() {
        val schema = schemaOf(ifNode(ValueSource.valueSpec(NodeTypeId("value.battery"))))

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
        val schema = schemaOf(ifNode(ValueSource.valueSpec(NodeTypeId("value.wifi"))))

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

    /** A placed `action.if` reading [source], with nothing wired into it. */
    private fun ifNode(source: String = ValueSource.WIRED_SPEC) = WorkflowNode(
        NodeId("if"), IF_TYPE_ID, "If", 0f, 0f,
        config = mapOf(IF_SOURCE_KEY to source),
    )

    private fun schemaOf(node: WorkflowNode): NodeConfigSchema {
        val definition = NodeTypeRegistry.byId(IF_TYPE_ID)!!
        return effectiveConfigSchema(definition, Workflow(nodes = listOf(node)), node)!!
    }

    private fun sourceFieldOptions(node: WorkflowNode): List<String> {
        val source = schemaOf(node).fields.single { it.key == IF_SOURCE_KEY }
        return (source.type as ConfigFieldType.ENUM).options.map { it.value }
    }
}
