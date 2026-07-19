package com.example.ottomatic.domain.registry

import com.example.ottomatic.domain.model.DataConnection
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
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
 *  - any ACTION node with [WorkflowNode.exposedInputs] exposes one typed DATA
 *    IN per exposed config field (schema derived from
 *    [ConfigSchemaRegistry] via [ConfigField.portSchema]).
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
    fun `exposed config field adds a typed DATA input port on an action`() {
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(
                    "n1", "action.notify", "Notify", 0f, 0f,
                    exposedInputs = setOf("text"),
                ),
            ),
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
            "Exposed 'text' should be a String schema, got: ${dataInputs.first { it.name == "text" }.schema}",
            dataInputs.first { it.name == "text" }.schema is
                com.example.ottomatic.domain.model.schema.ItemSchema.Primitive,
        )
    }

    @Test
    fun `non-exposed action has no DATA input ports`() {
        val workflow = Workflow(
            nodes = listOf(WorkflowNode("n1", "action.notify", "Notify", 0f, 0f)),
        )
        val node = workflow.node("n1")!!
        val def = NodeTypeRegistry.byId("action.notify")!!
        val dataInputs = effectiveInputPorts(def, workflow, node).filter {
            it.kind == com.example.ottomatic.domain.model.PortKind.DATA
        }
        assertTrue("Expected no DATA inputs, got: ${dataInputs.map { it.name }}", dataInputs.isEmpty())
    }

    @Test
    fun `trigger nodes do not gain exposed input ports even if exposedInputs is set`() {
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(
                    "n1", "trigger.sms", "SMS", 0f, 0f,
                    exposedInputs = setOf("sender"),
                ),
            ),
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
    fun `effectivePort resolves a wifi state DATA IN and DATA OUT with the same name by direction`() {
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(
                    "n1", "action.wifi", "Wifi", 0f, 0f,
                    exposedInputs = setOf("state"),
                ),
            ),
        )
        val node = workflow.node("n1")!!
        val def = NodeTypeRegistry.byId("action.wifi")!!
        val stateIn = effectivePort(def, workflow, node, "state", Direction.IN)
        val stateOut = effectivePort(def, workflow, node, "state", Direction.OUT)
        assertTrue("Expected state DATA IN", stateIn?.direction == Direction.IN)
        assertTrue("Expected state DATA OUT", stateOut?.direction == Direction.OUT)
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
                    exposedInputs = setOf(CONDITION_SOURCE_IN),
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
                    exposedInputs = setOf(CONDITION_SOURCE_IN),
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
    fun `condition type operator and field are not exposable but value is`() {
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
        assertFalse("type must not be exposable", fields[CONDITION_TYPE_CONFIG_KEY]!!.exposable)
        assertFalse("operator must not be exposable", fields["operator"]!!.exposable)
        assertFalse("field must not be exposable", fields["field"]!!.exposable)
        assertTrue("value must be exposable", fields["value"]!!.exposable)
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
    fun `condition source port schema follows the manual type`() {
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(
                    "cond", CONDITION_TYPE_ID, "If", 0f, 100f,
                    config = mapOf(CONDITION_TYPE_CONFIG_KEY to "int"),
                    exposedInputs = setOf(CONDITION_SOURCE_IN),
                ),
            ),
        )
        val node = workflow.node("cond")!!
        val def = NodeTypeRegistry.byId(CONDITION_TYPE_ID)!!
        val sourcePort = effectivePort(def, workflow, node, CONDITION_SOURCE_IN, Direction.IN)
        assertNotNull("source DATA input port should exist", sourcePort)
        val schema = sourcePort!!.schema
        assertTrue(
            "manual int should type the source port as Primitive(Int), got: $schema",
            schema is com.example.ottomatic.domain.model.schema.ItemSchema.Primitive &&
                schema.kClass == Int::class,
        )
    }

    @Test
    fun `condition source port schema is wildcard when auto and unconnected`() {
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(
                    "cond", CONDITION_TYPE_ID, "If", 0f, 100f,
                    exposedInputs = setOf(CONDITION_SOURCE_IN),
                ),
            ),
        )
        val node = workflow.node("cond")!!
        val def = NodeTypeRegistry.byId(CONDITION_TYPE_ID)!!
        val sourcePort = effectivePort(def, workflow, node, CONDITION_SOURCE_IN, Direction.IN)
        assertTrue(
            "auto + unconnected source should be Wildcard, got: ${sourcePort?.schema}",
            sourcePort?.schema is com.example.ottomatic.domain.model.schema.ItemSchema.Wildcard,
        )
    }
}
