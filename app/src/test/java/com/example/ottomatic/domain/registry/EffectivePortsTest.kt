package com.example.ottomatic.domain.registry

import com.example.ottomatic.domain.model.DataConnection
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
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
 *    its config schema from the data item connected to its `source` port.
 *
 * All other DATA input ports (url, text, to, body, ...) are declared
 * statically on the node type in [NodeTypeRegistry] and are always present.
 */
class EffectivePortsTest {

    @Test
    fun `break exposes per-field output ports once a struct is connected`() {
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode("n1", "trigger.sms", "SMS", 0f, 0f),
                WorkflowNode("n2", "action.break", "Break", 0f, 100f),
            ),
            dataConnections = listOf(
                DataConnection("d1", "n1", "sms", "n2", "struct"),
            ),
        )
        val breakNode = workflow.node("n2")!!
        val def = NodeTypeRegistry.byId(BREAK_TYPE_ID)!!
        val outputs = effectiveOutputPorts(def, workflow, breakNode).map { it.name }
        assertTrue("Expected per-field outputs, got: $outputs", outputs.contains("sender"))
        assertTrue("Expected per-field outputs, got: $outputs", outputs.contains("body"))
    }

    @Test
    fun `notify action has a first-class text DATA input port`() {
        val workflow = Workflow(
            nodes = listOf(WorkflowNode("n1", "action.notify", "Notify", 0f, 0f)),
        )
        val node = workflow.node("n1")!!
        val def = NodeTypeRegistry.byId("action.notify")!!
        val dataInputs = effectiveInputPorts(def, workflow, node).filter {
            it.kind == com.example.ottomatic.domain.model.PortKind.DATA
        }
        assertTrue(
            "Expected a 'text' DATA input, got: ${dataInputs.map { it.name }}",
            dataInputs.any { it.name == "text" },
        )
        assertTrue(
            "'text' should be a String schema, got: ${dataInputs.first { it.name == "text" }.schema}",
            dataInputs.first { it.name == "text" }.schema is
                com.example.ottomatic.domain.model.schema.ItemSchema.Primitive,
        )
    }

    @Test
    fun `trigger nodes have no DATA input ports`() {
        val workflow = Workflow(
            nodes = listOf(WorkflowNode("n1", "trigger.sms", "SMS", 0f, 0f)),
        )
        val node = workflow.node("n1")!!
        val def = NodeTypeRegistry.byId("trigger.sms")!!
        val dataInputs = effectiveInputPorts(def, workflow, node).filter {
            it.kind == com.example.ottomatic.domain.model.PortKind.DATA
        }
        assertTrue(
            "Triggers must not expose data inputs, got: ${dataInputs.map { it.name }}",
            dataInputs.isEmpty(),
        )
    }

    @Test
    fun `condition with a struct wired exposes a typed field dropdown narrowed operators and typed value`() {
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode("n1", "trigger.charging", "Charging", 0f, 0f),
                WorkflowNode(
                    "cond", CONDITION_TYPE_ID, "If", 0f, 100f,
                    config = mapOf(
                        CONDITION_TYPE_CONFIG_KEY to CONDITION_TYPE_AUTO,
                        "field" to "level",
                        "operator" to "greaterThan",
                        "value" to "20",
                    ),
                ),
            ),
            dataConnections = listOf(
                DataConnection("d1", "n1", "state", "cond", CONDITION_SOURCE_IN),
            ),
        )
        val node = workflow.node("cond")!!
        val def = NodeTypeRegistry.byId(CONDITION_TYPE_ID)!!
        val schema = effectiveConfigSchema(def, workflow, node)
        assertNotNull("Expected a dynamic config schema for the condition", schema)
        val fields = schema!!.fields.associateBy { it.key }

        assertNotNull("type chooser should be present", fields[CONDITION_TYPE_CONFIG_KEY])

        val fieldField = fields["field"]!!
        val fieldOptions = (fieldField.type as ConfigFieldType.ENUM).options
        assertTrue("field picker should list struct fields, got: $fieldOptions", fieldOptions.contains("level"))
        assertTrue("field picker should list struct fields, got: $fieldOptions", fieldOptions.contains("isCharging"))

        // 'level' is Int -> numeric operators only.
        val operatorField = fields["operator"]!!
        val operatorOptions = (operatorField.type as ConfigFieldType.ENUM).options
        assertTrue(
            "numeric operators should include greaterThan, got: $operatorOptions",
            operatorOptions.contains("greaterThan"),
        )
        assertTrue(
            "numeric operators should not include contains, got: $operatorOptions",
            !operatorOptions.contains("contains"),
        )

        // 'level' is Int -> INT compare-against.
        assertEquals(ConfigFieldType.INT, fields["value"]!!.type)
    }

    @Test
    fun `condition operator list narrows for a string field`() {
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode("n1", "trigger.sms", "SMS", 0f, 0f),
                WorkflowNode(
                    "cond", CONDITION_TYPE_ID, "If", 0f, 100f,
                    config = mapOf(
                        CONDITION_TYPE_CONFIG_KEY to CONDITION_TYPE_AUTO,
                        "field" to "sender",
                        "operator" to "contains",
                        "value" to "",
                    ),
                ),
            ),
            dataConnections = listOf(
                DataConnection("d1", "n1", "sms", "cond", CONDITION_SOURCE_IN),
            ),
        )
        val node = workflow.node("cond")!!
        val def = NodeTypeRegistry.byId(CONDITION_TYPE_ID)!!
        val schema = effectiveConfigSchema(def, workflow, node)!!
        val fields = schema.fields.associateBy { it.key }
        val operatorOptions = (fields["operator"]!!.type as ConfigFieldType.ENUM).options
        assertTrue(
            "string operators should include contains, got: $operatorOptions",
            operatorOptions.contains("contains"),
        )
        assertTrue(
            "string operators should include matchesRegex, got: $operatorOptions",
            operatorOptions.contains("matchesRegex"),
        )
        assertTrue(
            "string operators should not include greaterThan, got: $operatorOptions",
            !operatorOptions.contains("greaterThan"),
        )
        assertEquals(ConfigFieldType.STR, fields["value"]!!.type)
    }

    @Test
    fun `condition with no data edge and auto type still shows operator and value as string`() {
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode("cond", CONDITION_TYPE_ID, "If", 0f, 100f),
            ),
        )
        val node = workflow.node("cond")!!
        val def = NodeTypeRegistry.byId(CONDITION_TYPE_ID)!!
        val schema = effectiveConfigSchema(def, workflow, node)
        assertNotNull("schema should always be non-null for condition (type chooser)", schema)
        val fields = schema!!.fields.associateBy { it.key }
        assertNotNull("type chooser should be present", fields[CONDITION_TYPE_CONFIG_KEY])
        assertNotNull("operator should be present even without a connection", fields["operator"])
        assertNotNull("value should be present even without a connection", fields["value"])
        // Defaults to a string comparison when the type is unknown.
        assertEquals(ConfigFieldType.STR, fields["value"]!!.type)
        val operatorOptions = (fields["operator"]!!.type as ConfigFieldType.ENUM).options
        assertTrue(
            "default string operators should include equals, got: $operatorOptions",
            operatorOptions.contains("equals"),
        )
    }

    @Test
    fun `condition config schema has type operator field and value fields`() {
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode("n1", "trigger.charging", "Charging", 0f, 0f),
                WorkflowNode(
                    "cond", CONDITION_TYPE_ID, "If", 0f, 100f,
                    config = mapOf(
                        CONDITION_TYPE_CONFIG_KEY to CONDITION_TYPE_AUTO,
                        "field" to "level",
                    ),
                ),
            ),
            dataConnections = listOf(
                DataConnection("d1", "n1", "state", "cond", CONDITION_SOURCE_IN),
            ),
        )
        val node = workflow.node("cond")!!
        val def = NodeTypeRegistry.byId(CONDITION_TYPE_ID)!!
        val schema = effectiveConfigSchema(def, workflow, node)!!
        val fields = schema.fields.associateBy { it.key }
        assertNotNull("type chooser should be present", fields[CONDITION_TYPE_CONFIG_KEY])
        assertNotNull("operator should be present", fields["operator"])
        assertNotNull("field picker should be present for struct source", fields["field"])
        assertNotNull("value should be present", fields["value"])
    }

    @Test
    fun `condition with manual int type exposes numeric operators and INT value without a connection`() {
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(
                    "cond", CONDITION_TYPE_ID, "If", 0f, 100f,
                    config = mapOf(
                        CONDITION_TYPE_CONFIG_KEY to "int",
                        "operator" to "greaterThan",
                        "value" to "20",
                    ),
                ),
            ),
        )
        val node = workflow.node("cond")!!
        val def = NodeTypeRegistry.byId(CONDITION_TYPE_ID)!!
        val schema = effectiveConfigSchema(def, workflow, node)!!
        val fields = schema.fields.associateBy { it.key }
        assertNotNull("type chooser should be present", fields[CONDITION_TYPE_CONFIG_KEY])
        assertNull("no field picker in manual mode", fields["field"])
        val operatorOptions = (fields["operator"]!!.type as ConfigFieldType.ENUM).options
        assertTrue(
            "manual int should give numeric operators, got: $operatorOptions",
            operatorOptions.contains("greaterThan"),
        )
        assertEquals(ConfigFieldType.INT, fields["value"]!!.type)
    }

    @Test
    fun `condition with manual boolean type exposes equality operators and BOOL value`() {
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(
                    "cond", CONDITION_TYPE_ID, "If", 0f, 100f,
                    config = mapOf(CONDITION_TYPE_CONFIG_KEY to "boolean"),
                ),
            ),
        )
        val node = workflow.node("cond")!!
        val def = NodeTypeRegistry.byId(CONDITION_TYPE_ID)!!
        val schema = effectiveConfigSchema(def, workflow, node)!!
        val fields = schema.fields.associateBy { it.key }
        val operatorOptions = (fields["operator"]!!.type as ConfigFieldType.ENUM).options
        assertEquals(
            "boolean operators are equals/notEquals, got: $operatorOptions",
            listOf("equals", "notEquals"),
            operatorOptions,
        )
        assertEquals(ConfigFieldType.BOOL, fields["value"]!!.type)
    }

    @Test
    fun `condition source and value port schemas follow the manual type`() {
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(
                    "cond", CONDITION_TYPE_ID, "If", 0f, 100f,
                    config = mapOf(CONDITION_TYPE_CONFIG_KEY to "int"),
                ),
            ),
        )
        val node = workflow.node("cond")!!
        val def = NodeTypeRegistry.byId(CONDITION_TYPE_ID)!!
        val sourcePort = effectivePort(def, workflow, node, CONDITION_SOURCE_IN, Direction.IN)
        assertNotNull("source DATA input port should exist", sourcePort)
        val sourceSchema = sourcePort!!.schema
        assertTrue(
            "manual int should type the source port as Primitive(Int), got: $sourceSchema",
            sourceSchema is com.example.ottomatic.domain.model.schema.ItemSchema.Primitive &&
                sourceSchema.kClass == Int::class,
        )
        val valuePort = effectivePort(def, workflow, node, "value", Direction.IN)
        assertNotNull("value DATA input port should exist", valuePort)
        val valueSchema = valuePort!!.schema
        assertTrue(
            "manual int should type the value port as Primitive(Int), got: $valueSchema",
            valueSchema is com.example.ottomatic.domain.model.schema.ItemSchema.Primitive &&
                valueSchema.kClass == Int::class,
        )
    }

    @Test
    fun `condition source and value port schemas are wildcard when auto and unconnected`() {
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode("cond", CONDITION_TYPE_ID, "If", 0f, 100f),
            ),
        )
        val node = workflow.node("cond")!!
        val def = NodeTypeRegistry.byId(CONDITION_TYPE_ID)!!
        val sourcePort = effectivePort(def, workflow, node, CONDITION_SOURCE_IN, Direction.IN)
        assertTrue(
            "auto + unconnected source should be Wildcard, got: ${sourcePort?.schema}",
            sourcePort?.schema is com.example.ottomatic.domain.model.schema.ItemSchema.Wildcard,
        )
        val valuePort = effectivePort(def, workflow, node, "value", Direction.IN)
        assertTrue(
            "auto + unconnected value should be Wildcard, got: ${valuePort?.schema}",
            valuePort?.schema is com.example.ottomatic.domain.model.schema.ItemSchema.Wildcard,
        )
    }
}
