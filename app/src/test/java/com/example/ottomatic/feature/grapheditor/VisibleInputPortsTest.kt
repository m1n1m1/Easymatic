package com.example.ottomatic.feature.grapheditor

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.DataConnection
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.registry.NodeTypeRegistry
import com.example.ottomatic.domain.registry.effectiveInputPorts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleInputPortsTest {

    @Test
    fun `disconnected DATA inputs are hidden by default`() {
        val workflow = Workflow(
            nodes = listOf(WorkflowNode(NodeId("notify"), NodeTypeId("action.notify"), "Notify", 0f, 0f)),
        )
        val node = workflow.node(NodeId("notify"))!!
        val definition = NodeTypeRegistry.byId(node.typeId)!!

        val inputs = visibleInputPorts(definition, workflow, node)

        assertEquals(listOf(PortName("in")), inputs.map { it.name })
        assertTrue(effectiveInputPorts(definition, workflow, node).any { it.name == PortName("text") })
    }

    @Test
    fun `selected DATA input is shown`() {
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(
                    NodeId("notify"),
                    NodeTypeId("action.notify"),
                    "Notify",
                    0f,
                    0f,
                    visibleDataInputs = setOf(PortName("text")),
                ),
            ),
        )
        val node = workflow.node(NodeId("notify"))!!
        val definition = NodeTypeRegistry.byId(node.typeId)!!

        val inputs = visibleInputPorts(definition, workflow, node)

        assertTrue(inputs.any { it.name == PortName("text") && it.kind == PortKind.DATA })
    }

    @Test
    fun `a declared script input is always shown, because it has no form row to opt in from`() {
        // A script's inputs are named in its `@Ports` config, not declared as
        // `@Wired` properties, so the form has no socket toggle for them. Hidden
        // by default they could never be revealed at all.
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(
                    NodeId("s"), NodeTypeId("action.script"), "Run Script", 0f, 0f,
                    config = mapOf(ConfigKey("inputs") to "battery:WHOLE_NUMBER\npayload:ANY"),
                ),
            ),
        )
        val node = workflow.node(NodeId("s"))!!
        val definition = NodeTypeRegistry.byId(node.typeId)!!

        val inputs = visibleInputPorts(definition, workflow, node).map { it.name }

        assertTrue(inputs.toString(), inputs.containsAll(listOf(PortName("battery"), PortName("payload"))))
    }

    @Test
    fun `the struct a break reads is shown too`() {
        val workflow = Workflow(
            nodes = listOf(WorkflowNode(NodeId("b"), NodeTypeId("action.break"), "Break", 0f, 0f)),
        )
        val node = workflow.node(NodeId("b"))!!
        val definition = NodeTypeRegistry.byId(node.typeId)!!

        val inputs = visibleInputPorts(definition, workflow, node).map { it.name }

        assertTrue(inputs.toString(), inputs.contains(PortName("struct")))
    }

    @Test
    fun `connected DATA input remains hidden until selected`() {
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("source"), NodeTypeId("trigger.schedule"), "Schedule", 0f, 0f),
                WorkflowNode(NodeId("notify"), NodeTypeId("action.notify"), "Notify", 0f, 100f),
            ),
            dataConnections = listOf(
                DataConnection("data", NodeId("source"), PortName("time"), NodeId("notify"), PortName("text")),
            ),
        )
        val node = workflow.node(NodeId("notify"))!!
        val definition = NodeTypeRegistry.byId(node.typeId)!!

        val inputs = visibleInputPorts(definition, workflow, node)

        assertTrue(inputs.none { it.name == PortName("text") && it.kind == PortKind.DATA })
    }
}
