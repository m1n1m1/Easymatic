package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.DataConnection
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.ComparisonOperator
import com.example.ottomatic.domain.model.config.ComparisonType
import com.example.ottomatic.domain.model.items.SmsMessage
import com.example.ottomatic.domain.model.schema.DateTime
import com.example.ottomatic.domain.model.schema.ItemSchema
import com.example.ottomatic.domain.model.schema.schemaOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the effective-port resolution for the two dynamic mechanisms:
 *
 *  - `action.break` exposes one DATA OUT per field of the struct connected to
 *    its `struct` input (schema derived from the incoming edge);
 *  - `action.if` rewrites its `source`/`value` DATA IN port schemas and
 *    narrows the config form derived from `CompareConfig` against the data
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
    fun `break only accepts a struct`() {
        // Its input is "any object", not a wildcard: breaking a date or a number
        // into fields is meaningless, and a wildcard would let the user wire one up
        // and then wonder why no output ports appeared.
        val breakIn = breakStructPort()
        assertTrue(isDataAssignable(structOut(schemaOf<SmsMessage>()), breakIn))
        assertFalse(isDataAssignable(structOut(ItemSchema.Primitive(DateTime::class)), breakIn))
        assertFalse(isDataAssignable(structOut(ItemSchema.Primitive(Int::class)), breakIn))
        assertFalse(isDataAssignable(structOut(ItemSchema.ListSchema(ItemSchema.Primitive(Int::class))), breakIn))
    }

    @Test
    fun `break still accepts a source that does not know its own type yet`() {
        // An adaptive transform is a wildcard until it is retyped, so refusing it
        // here would make the order the user wires things in matter.
        assertTrue(isDataAssignable(structOut(ItemSchema.Wildcard), breakStructPort()))
    }

    private fun breakStructPort(): Port {
        val def = NodeTypeRegistry.byId(BREAK_TYPE_ID)!!
        val node = WorkflowNode(NodeId("b"), BREAK_TYPE_ID, "Break", 0f, 0f)
        return effectiveInputPorts(def, Workflow(nodes = listOf(node)), node)
            .single { it.name == BREAK_STRUCT_IN }
    }

    private fun structOut(schema: ItemSchema) =
        Port(PortName("out"), PortKind.DATA, Direction.OUT, schema)

    @Test
    fun `a conversion takes its output type from its own config`() {
        val workflow = Workflow(nodes = listOf(convertNode(to = "WHOLE_NUMBER")))
        assertEquals(
            ItemSchema.Primitive(Int::class),
            convertOutput(workflow),
        )
    }

    @Test
    fun `a conversion narrows to the consuming port inside the same family`() {
        // "Whole number" is one choice in the form, but a Long port has to receive a
        // Long for the edge to type-check. `trigger.sms`'s struct carries a Long
        // `timestamp`, so `action.if` wired to it exposes a Long compare port.
        val workflow = Workflow(
            nodes = listOf(
                convertNode(to = "WHOLE_NUMBER"),
                WorkflowNode(NodeId("s"), NodeTypeId("trigger.sms"), "SMS", 0f, 0f),
                WorkflowNode(
                    NodeId("i"), IF_TYPE_ID, "If", 0f, 100f,
                    config = mapOf(IF_TYPE_CONFIG_KEY to ComparisonType.LONG.name),
                ),
            ),
            dataConnections = listOf(
                DataConnection("d1", NodeId("c"), TRANSFORM_OUT, NodeId("i"), IF_VALUE_IN),
            ),
        )
        assertEquals(ItemSchema.Primitive(Long::class), convertOutput(workflow))
    }

    @Test
    fun `a conversion keeps its family default when the consumer is a different family`() {
        val workflow = Workflow(
            nodes = listOf(
                convertNode(to = "WHOLE_NUMBER"),
                WorkflowNode(NodeId("n"), NodeTypeId("action.notify"), "Notify", 0f, 100f),
            ),
            dataConnections = listOf(
                DataConnection("d1", NodeId("c"), TRANSFORM_OUT, NodeId("n"), PortName("text")),
            ),
        )
        assertEquals(ItemSchema.Primitive(Int::class), convertOutput(workflow))
    }

    private fun convertNode(to: String) = WorkflowNode(
        NodeId("c"), CONVERT_TYPE_ID, "Convert", 0f, 0f,
        config = mapOf(CONVERT_TO_KEY to to),
    )

    private fun convertOutput(workflow: Workflow): ItemSchema? {
        val node = workflow.node(NodeId("c"))!!
        val def = NodeTypeRegistry.byId(CONVERT_TYPE_ID)!!
        return effectiveOutputPorts(def, workflow, node).single { it.name == TRANSFORM_OUT }.schema
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
    fun `the comparison with a struct wired exposes a typed field dropdown narrowed operators and typed value`() {
        val workflow = ifWorkflow(
            sourceTypeId = NodeTypeId("trigger.charging"),
            sourcePort = PortName("state"),
            config = mapOf(
                IF_TYPE_CONFIG_KEY to ComparisonType.AUTO.name,
                IF_FIELD_KEY to "level",
                IF_OPERATOR_KEY to ComparisonOperator.GREATER_THAN.name,
                ConfigKey(IF_VALUE_IN.value) to "20",
            ),
        )
        val fields = ifFields(workflow)

        assertNotNull("type chooser should be present", fields[IF_TYPE_CONFIG_KEY])

        val fieldOptions = fields.optionsOf(IF_FIELD_KEY)
        assertTrue("field picker should list struct fields, got: $fieldOptions", "level" in fieldOptions)
        assertTrue("field picker should list struct fields, got: $fieldOptions", "isCharging" in fieldOptions)

        // 'level' is Int -> ordering operators, no text operators.
        val operators = fields.optionsOf(IF_OPERATOR_KEY)
        assertTrue("numeric operators should allow ordering, got: $operators", GREATER_THAN in operators)
        assertTrue("numeric operators should exclude contains, got: $operators", CONTAINS !in operators)

        assertEquals(ConfigFieldType.INT, fields.getValue(ConfigKey(IF_VALUE_IN.value)).type)
    }

    @Test
    fun `the comparison operator list narrows for a string field`() {
        val workflow = ifWorkflow(
            sourceTypeId = NodeTypeId("trigger.sms"),
            sourcePort = PortName("sms"),
            config = mapOf(
                IF_TYPE_CONFIG_KEY to ComparisonType.AUTO.name,
                IF_FIELD_KEY to "sender",
            ),
        )
        val fields = ifFields(workflow)

        val operators = fields.optionsOf(IF_OPERATOR_KEY)
        assertTrue("string operators should include contains, got: $operators", CONTAINS in operators)
        assertTrue("string operators should include regex, got: $operators", MATCHES_REGEX in operators)
        assertTrue("string operators should exclude ordering, got: $operators", GREATER_THAN !in operators)
        assertEquals(ConfigFieldType.STR, fields.getValue(ConfigKey(IF_VALUE_IN.value)).type)
    }

    @Test
    fun `the comparison with no data edge and auto type still shows operator and value as string`() {
        val workflow = Workflow(nodes = listOf(WorkflowNode(NodeId("cond"), IF_TYPE_ID, "If", 0f, 100f)))
        val fields = ifFields(workflow)

        assertNotNull("type chooser should be present", fields[IF_TYPE_CONFIG_KEY])
        assertNotNull("operator should be present even without a connection", fields[IF_OPERATOR_KEY])
        assertNotNull("value should be present even without a connection", fields[ConfigKey(IF_VALUE_IN.value)])
        assertEquals(ConfigFieldType.STR, fields.getValue(ConfigKey(IF_VALUE_IN.value)).type)
        assertTrue(EQUALS in fields.optionsOf(IF_OPERATOR_KEY))
    }

    @Test
    fun `the comparison drops the field picker when the source is not a struct`() {
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(
                    NodeId("cond"), IF_TYPE_ID, "If", 0f, 100f,
                    config = mapOf(IF_TYPE_CONFIG_KEY to ComparisonType.INT.name),
                ),
            ),
        )
        val fields = ifFields(workflow)

        assertNull("no field picker for a pinned primitive", fields[IF_FIELD_KEY])
        assertTrue(GREATER_THAN in fields.optionsOf(IF_OPERATOR_KEY))
        assertEquals(ConfigFieldType.INT, fields.getValue(ConfigKey(IF_VALUE_IN.value)).type)
    }

    @Test
    fun `the comparison with a pinned boolean type exposes equality operators and a BOOL value`() {
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(
                    NodeId("cond"), IF_TYPE_ID, "If", 0f, 100f,
                    config = mapOf(IF_TYPE_CONFIG_KEY to ComparisonType.BOOLEAN.name),
                ),
            ),
        )
        val fields = ifFields(workflow)

        assertEquals(listOf(EQUALS, NOT_EQUALS), fields.optionsOf(IF_OPERATOR_KEY))
        assertEquals(ConfigFieldType.BOOL, fields.getValue(ConfigKey(IF_VALUE_IN.value)).type)
    }

    @Test
    fun `the comparison source and value port schemas follow the pinned type`() {
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(
                    NodeId("cond"), IF_TYPE_ID, "If", 0f, 100f,
                    config = mapOf(IF_TYPE_CONFIG_KEY to ComparisonType.INT.name),
                ),
            ),
        )
        val node = workflow.node(NodeId("cond"))!!
        val def = NodeTypeRegistry.byId(IF_TYPE_ID)!!

        for (portName in listOf(IF_SOURCE_IN, IF_VALUE_IN)) {
            val schema = effectivePort(def, workflow, node, portName, Direction.IN)?.schema
            assertTrue(
                "pinned int should type '$portName' as Primitive(Int), got: $schema",
                schema is ItemSchema.Primitive && schema.kClass == Int::class,
            )
        }
    }

    @Test
    fun `the comparison source and value port schemas are wildcard when auto and unconnected`() {
        val workflow = Workflow(nodes = listOf(WorkflowNode(NodeId("cond"), IF_TYPE_ID, "If", 0f, 100f)))
        val node = workflow.node(NodeId("cond"))!!
        val def = NodeTypeRegistry.byId(IF_TYPE_ID)!!

        for (portName in listOf(IF_SOURCE_IN, IF_VALUE_IN)) {
            val schema = effectivePort(def, workflow, node, portName, Direction.IN)?.schema
            assertTrue("auto + unconnected '$portName' should be Wildcard, got: $schema", schema is ItemSchema.Wildcard)
        }
    }

    private fun ifWorkflow(
        sourceTypeId: NodeTypeId,
        sourcePort: PortName,
        config: Map<ConfigKey, String>,
    ) = Workflow(
        nodes = listOf(
            WorkflowNode(NodeId("n1"), sourceTypeId, "Source", 0f, 0f),
            WorkflowNode(NodeId("cond"), IF_TYPE_ID, "If", 0f, 100f, config = config),
        ),
        dataConnections = listOf(
            DataConnection("d1", NodeId("n1"), sourcePort, NodeId("cond"), IF_SOURCE_IN),
        ),
    )

    private fun ifFields(workflow: Workflow): Map<ConfigKey, ConfigField<*>> {
        val node = workflow.node(NodeId("cond"))!!
        val def = NodeTypeRegistry.byId(IF_TYPE_ID)!!
        val schema = effectiveConfigSchema(def, workflow, node)
        assertNotNull("action.if should always expose a config schema", schema)
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
