package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.DataConnection
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.ComparisonOperator
import com.example.ottomatic.domain.model.config.ComparisonType
import com.example.ottomatic.domain.model.schema.ItemSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the effective-port resolution for the two dynamic mechanisms:
 *
 *  - `action.break` exposes one DATA OUT per field of the struct connected to
 *    its `struct` input (schema derived from the incoming edge);
 *  - `action.condition` rewrites its `source`/`value` DATA IN port schemas and
 *    narrows the config form derived from `ConditionConfig` against the data
 *    item connected to its `source` port.
 *
 * All other DATA input ports are derived from the `@Wired` properties of a
 * node's config class and are always present.
 */
class EffectivePortsTest {

    @Test
    fun `break exposes per-field output ports once a struct is connected`() {
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("n1"), NodeTypeId("trigger.sms"), "SMS", 0f, 0f),
                WorkflowNode(NodeId("n2"), BREAK_TYPE_ID, "Break", 0f, 100f),
            ),
            dataConnections = listOf(
                DataConnection("d1", NodeId("n1"), PortName("sms"), NodeId("n2"), BREAK_STRUCT_IN),
            ),
        )
        val breakNode = workflow.node(NodeId("n2"))!!
        val def = NodeTypeRegistry.byId(BREAK_TYPE_ID)!!
        val outputs = effectiveOutputPorts(def, workflow, breakNode).map { it.name }
        assertTrue("Expected per-field outputs, got: $outputs", outputs.contains(PortName("sender")))
        assertTrue("Expected per-field outputs, got: $outputs", outputs.contains(PortName("body")))
    }

    @Test
    fun `a wired config property becomes a typed DATA input port`() {
        val workflow = Workflow(
            nodes = listOf(WorkflowNode(NodeId("n1"), NodeTypeId("action.notify"), "Notify", 0f, 0f)),
        )
        val node = workflow.node(NodeId("n1"))!!
        val def = NodeTypeRegistry.byId(NodeTypeId("action.notify"))!!
        val dataInputs = effectiveInputPorts(def, workflow, node).filter { it.kind == PortKind.DATA }
        val text = dataInputs.firstOrNull { it.name == PortName("text") }
        assertNotNull("Expected a 'text' DATA input, got: ${dataInputs.map { it.name }}", text)
        assertTrue("'text' should be a String schema, got: ${text?.schema}", text?.schema is ItemSchema.Primitive)
    }

    @Test
    fun `a config property that is not wired does not become a port`() {
        val workflow = Workflow(
            nodes = listOf(WorkflowNode(NodeId("n1"), NodeTypeId("action.notify"), "Notify", 0f, 0f)),
        )
        val node = workflow.node(NodeId("n1"))!!
        val def = NodeTypeRegistry.byId(NodeTypeId("action.notify"))!!
        val dataInputs = effectiveInputPorts(def, workflow, node).filter { it.kind == PortKind.DATA }
        assertTrue(
            "'title' is not @Wired and must not be a port, got: ${dataInputs.map { it.name }}",
            dataInputs.none { it.name == PortName("title") },
        )
    }

    @Test
    fun `trigger nodes have no DATA input ports`() {
        val workflow = Workflow(nodes = listOf(WorkflowNode(NodeId("n1"), NodeTypeId("trigger.sms"), "SMS", 0f, 0f)))
        val node = workflow.node(NodeId("n1"))!!
        val def = NodeTypeRegistry.byId(NodeTypeId("trigger.sms"))!!
        val dataInputs = effectiveInputPorts(def, workflow, node).filter { it.kind == PortKind.DATA }
        assertTrue("Triggers must not expose data inputs, got: ${dataInputs.map { it.name }}", dataInputs.isEmpty())
    }

    @Test
    fun `condition with a struct wired exposes a typed field dropdown narrowed operators and typed value`() {
        val workflow = conditionWorkflow(
            sourceTypeId = NodeTypeId("trigger.charging"),
            sourcePort = PortName("state"),
            config = mapOf(
                CONDITION_TYPE_CONFIG_KEY to ComparisonType.AUTO.name,
                CONDITION_FIELD_KEY to "level",
                CONDITION_OPERATOR_KEY to ComparisonOperator.GREATER_THAN.name,
                ConfigKey(CONDITION_VALUE_IN.value) to "20",
            ),
        )
        val fields = conditionFields(workflow)

        assertNotNull("type chooser should be present", fields[CONDITION_TYPE_CONFIG_KEY])

        val fieldOptions = fields.optionsOf(CONDITION_FIELD_KEY)
        assertTrue("field picker should list struct fields, got: $fieldOptions", "level" in fieldOptions)
        assertTrue("field picker should list struct fields, got: $fieldOptions", "isCharging" in fieldOptions)

        // 'level' is Int -> ordering operators, no text operators.
        val operators = fields.optionsOf(CONDITION_OPERATOR_KEY)
        assertTrue("numeric operators should allow ordering, got: $operators", GREATER_THAN in operators)
        assertTrue("numeric operators should exclude contains, got: $operators", CONTAINS !in operators)

        assertEquals(ConfigFieldType.INT, fields.getValue(ConfigKey(CONDITION_VALUE_IN.value)).type)
    }

    @Test
    fun `condition operator list narrows for a string field`() {
        val workflow = conditionWorkflow(
            sourceTypeId = NodeTypeId("trigger.sms"),
            sourcePort = PortName("sms"),
            config = mapOf(
                CONDITION_TYPE_CONFIG_KEY to ComparisonType.AUTO.name,
                CONDITION_FIELD_KEY to "sender",
            ),
        )
        val fields = conditionFields(workflow)

        val operators = fields.optionsOf(CONDITION_OPERATOR_KEY)
        assertTrue("string operators should include contains, got: $operators", CONTAINS in operators)
        assertTrue("string operators should include regex, got: $operators", MATCHES_REGEX in operators)
        assertTrue("string operators should exclude ordering, got: $operators", GREATER_THAN !in operators)
        assertEquals(ConfigFieldType.STR, fields.getValue(ConfigKey(CONDITION_VALUE_IN.value)).type)
    }

    @Test
    fun `condition with no data edge and auto type still shows operator and value as string`() {
        val workflow = Workflow(nodes = listOf(WorkflowNode(NodeId("cond"), CONDITION_TYPE_ID, "If", 0f, 100f)))
        val fields = conditionFields(workflow)

        assertNotNull("type chooser should be present", fields[CONDITION_TYPE_CONFIG_KEY])
        assertNotNull("operator should be present even without a connection", fields[CONDITION_OPERATOR_KEY])
        assertNotNull("value should be present even without a connection", fields[ConfigKey(CONDITION_VALUE_IN.value)])
        assertEquals(ConfigFieldType.STR, fields.getValue(ConfigKey(CONDITION_VALUE_IN.value)).type)
        assertTrue(EQUALS in fields.optionsOf(CONDITION_OPERATOR_KEY))
    }

    @Test
    fun `condition drops the field picker when the source is not a struct`() {
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(
                    NodeId("cond"), CONDITION_TYPE_ID, "If", 0f, 100f,
                    config = mapOf(CONDITION_TYPE_CONFIG_KEY to ComparisonType.INT.name),
                ),
            ),
        )
        val fields = conditionFields(workflow)

        assertNull("no field picker for a pinned primitive", fields[CONDITION_FIELD_KEY])
        assertTrue(GREATER_THAN in fields.optionsOf(CONDITION_OPERATOR_KEY))
        assertEquals(ConfigFieldType.INT, fields.getValue(ConfigKey(CONDITION_VALUE_IN.value)).type)
    }

    @Test
    fun `condition with a pinned boolean type exposes equality operators and a BOOL value`() {
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(
                    NodeId("cond"), CONDITION_TYPE_ID, "If", 0f, 100f,
                    config = mapOf(CONDITION_TYPE_CONFIG_KEY to ComparisonType.BOOLEAN.name),
                ),
            ),
        )
        val fields = conditionFields(workflow)

        assertEquals(listOf(EQUALS, NOT_EQUALS), fields.optionsOf(CONDITION_OPERATOR_KEY))
        assertEquals(ConfigFieldType.BOOL, fields.getValue(ConfigKey(CONDITION_VALUE_IN.value)).type)
    }

    @Test
    fun `condition source and value port schemas follow the pinned type`() {
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(
                    NodeId("cond"), CONDITION_TYPE_ID, "If", 0f, 100f,
                    config = mapOf(CONDITION_TYPE_CONFIG_KEY to ComparisonType.INT.name),
                ),
            ),
        )
        val node = workflow.node(NodeId("cond"))!!
        val def = NodeTypeRegistry.byId(CONDITION_TYPE_ID)!!

        for (portName in listOf(CONDITION_SOURCE_IN, CONDITION_VALUE_IN)) {
            val schema = effectivePort(def, workflow, node, portName, Direction.IN)?.schema
            assertTrue(
                "pinned int should type '$portName' as Primitive(Int), got: $schema",
                schema is ItemSchema.Primitive && schema.kClass == Int::class,
            )
        }
    }

    @Test
    fun `condition source and value port schemas are wildcard when auto and unconnected`() {
        val workflow = Workflow(nodes = listOf(WorkflowNode(NodeId("cond"), CONDITION_TYPE_ID, "If", 0f, 100f)))
        val node = workflow.node(NodeId("cond"))!!
        val def = NodeTypeRegistry.byId(CONDITION_TYPE_ID)!!

        for (portName in listOf(CONDITION_SOURCE_IN, CONDITION_VALUE_IN)) {
            val schema = effectivePort(def, workflow, node, portName, Direction.IN)?.schema
            assertTrue("auto + unconnected '$portName' should be Wildcard, got: $schema", schema is ItemSchema.Wildcard)
        }
    }

    private fun conditionWorkflow(
        sourceTypeId: NodeTypeId,
        sourcePort: PortName,
        config: Map<ConfigKey, String>,
    ) = Workflow(
        nodes = listOf(
            WorkflowNode(NodeId("n1"), sourceTypeId, "Source", 0f, 0f),
            WorkflowNode(NodeId("cond"), CONDITION_TYPE_ID, "If", 0f, 100f, config = config),
        ),
        dataConnections = listOf(
            DataConnection("d1", NodeId("n1"), sourcePort, NodeId("cond"), CONDITION_SOURCE_IN),
        ),
    )

    private fun conditionFields(workflow: Workflow): Map<ConfigKey, ConfigField<*>> {
        val node = workflow.node(NodeId("cond"))!!
        val def = NodeTypeRegistry.byId(CONDITION_TYPE_ID)!!
        val schema = effectiveConfigSchema(def, workflow, node)
        assertNotNull("condition should always expose a config schema", schema)
        return schema!!.fields.associateBy { it.key }
    }

    private fun Map<ConfigKey, ConfigField<*>>.optionsOf(key: ConfigKey): List<String> =
        (getValue(key).type as ConfigFieldType.ENUM).options.map { it.value }

    private companion object {
        val EQUALS = ComparisonOperator.EQUALS.name
        val NOT_EQUALS = ComparisonOperator.NOT_EQUALS.name
        val GREATER_THAN = ComparisonOperator.GREATER_THAN.name
        val CONTAINS = ComparisonOperator.CONTAINS.name
        val MATCHES_REGEX = ComparisonOperator.MATCHES_REGEX.name
    }
}
