package com.example.ottomatic.domain.registry

import com.example.ottomatic.domain.model.DataConnection
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
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
}
