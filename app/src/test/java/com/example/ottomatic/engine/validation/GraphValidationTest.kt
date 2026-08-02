package com.example.ottomatic.engine.validation

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.DataConnection
import com.example.ottomatic.domain.model.ExecConnection
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.config.ValueType
import com.example.ottomatic.domain.registry.CONVERT_IN
import com.example.ottomatic.domain.registry.CONVERT_TO_KEY
import com.example.ottomatic.domain.registry.CONVERT_TYPE_ID
import com.example.ottomatic.domain.registry.TRANSFORM_OUT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The half of validation the executor and the editor both consume: who to badge,
 * and what must not run.
 *
 * The messages are [GraphValidatorTest]'s business. What is pinned here is the
 * quarantine — a rule that blocks too much silently deletes work the user can see
 * is fine, and a rule that blocks too little runs a node on data it was never
 * given.
 */
class GraphValidationTest {

    @Test
    fun `errors and warnings are separated and severityFor prefers the error`() {
        val node = NodeId("n")
        val validation = GraphValidation(
            listOf(
                ValidationIssue(Severity.WARNING, "warn", nodes = setOf(node)),
                ValidationIssue(Severity.ERROR, "err", nodes = setOf(node)),
            ),
        )

        assertEquals(1, validation.errors.size)
        assertEquals(1, validation.warnings.size)
        assertEquals(2, validation.issuesFor(node).size)
        // A card can only wear one badge, and "broken" outranks "unfinished".
        assertEquals(Severity.ERROR, validation.severityFor(node))
        assertNull(validation.severityFor(NodeId("other")))
        assertFalse(validation.isRunnable)
    }

    @Test
    fun `an empty validation blocks nothing and is runnable`() {
        assertTrue(GraphValidation.EMPTY.isRunnable)
        assertTrue(GraphValidation.EMPTY.isEmpty)
        assertTrue(GraphValidation.EMPTY.blockedNodes.isEmpty())
        assertTrue(GraphValidation.EMPTY.blockedConnections.isEmpty())
    }

    /**
     * The contract that keeps a warning a warning. Nothing in the palette is
     * allowed to be "a bit broken": if a finding stops something running it is an
     * error, and the executor's skip lines say so.
     */
    @Test
    fun `no warning ever quarantines anything`() {
        val workflows = listOf(
            starvedTransform(),
            unreadValue(),
            Workflow(nodes = listOf(WorkflowNode(NodeId("n"), NodeTypeId("action.notify"), "N", 0f, 0f))),
        )
        for (workflow in workflows) {
            val warnings = GraphValidator(workflow).validate().warnings
            assertTrue("expected warnings in $workflow", warnings.isNotEmpty())
            assertTrue(
                warnings.toString(),
                warnings.all { it.blockedNodes.isEmpty() && it.blockedConnections.isEmpty() },
            )
        }
    }

    @Test
    fun `an exec cycle names its nodes and blocks only the edge that closes it`() {
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("n1"), NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
                WorkflowNode(NodeId("n2"), NodeTypeId("action.notify"), "Notify", 0f, 100f),
            ),
            execConnections = listOf(
                ExecConnection("c1", NodeId("n1"), PortName("out"), NodeId("n2"), PortName("in")),
                ExecConnection("c2", NodeId("n2"), PortName("out"), NodeId("n1"), PortName("in")),
            ),
        )
        val cycle = GraphValidator(workflow).validate().errors
            .single { it.message.contains("Execution cycle") }

        assertEquals(setOf(NodeId("n1"), NodeId("n2")), cycle.nodes)
        // The wire back to a node already running, not the one that entered the loop.
        assertEquals("c2", cycle.connectionId)
        assertEquals(setOf("c2"), cycle.blockedConnections)
        // Blocking the nodes would delete a chain of steps over one wire too many.
        assertTrue(cycle.blockedNodes.isEmpty())
    }

    /** One search reports one loop; a graph with two must not leave one live. */
    @Test
    fun `two independent exec cycles are both reported`() {
        val workflow = Workflow(
            nodes = listOf("a", "b", "c", "d").map {
                WorkflowNode(NodeId(it), NodeTypeId("action.notify"), it, 0f, 0f)
            },
            execConnections = listOf(
                ExecConnection("ab", NodeId("a"), PortName("out"), NodeId("b"), PortName("in")),
                ExecConnection("ba", NodeId("b"), PortName("out"), NodeId("a"), PortName("in")),
                ExecConnection("cd", NodeId("c"), PortName("out"), NodeId("d"), PortName("in")),
                ExecConnection("dc", NodeId("d"), PortName("out"), NodeId("c"), PortName("in")),
            ),
        )
        val validation = GraphValidator(workflow).validate()

        assertEquals(2, validation.errors.count { it.message.contains("Execution cycle") })
        assertEquals(setOf("ba", "dc"), validation.blockedConnections)
    }

    /**
     * A mismatched wire holds back the node *reading* it. The producer is fine —
     * it just cannot be plugged in here — and stopping it too would quarantine a
     * branch that has nothing wrong with it.
     */
    @Test
    fun `a schema mismatch blocks the consumer, not the source`() {
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("t"), NodeTypeId("trigger.sms"), "SMS", 0f, 0f),
                WorkflowNode(
                    NodeId("n"), NodeTypeId("action.notify"), "Notify", 0f, 100f,
                    config = mapOf(ConfigKey("title") to "T"),
                    visibleDataInputs = setOf(PortName("text")),
                ),
            ),
            execConnections = listOf(
                ExecConnection("e1", NodeId("t"), PortName("out"), NodeId("n"), PortName("in")),
            ),
            // The whole struct into a Text input: nothing converts a struct.
            dataConnections = listOf(
                DataConnection("d1", NodeId("t"), PortName("sms"), NodeId("n"), PortName("text")),
            ),
        )
        val issue = GraphValidator(workflow).validate().errors.single()

        assertEquals(setOf(NodeId("n")), issue.blockedNodes)
        assertEquals(setOf("d1"), issue.blockedConnections)
        assertEquals("d1", issue.connectionId)
    }

    /**
     * A transform has no exec position, so blocking *it* would stop nothing. The
     * quarantine has to land at the far end of the chain, on whatever actually
     * runs — which is what `executedConsumers` is for.
     */
    @Test
    fun `a mismatch behind a transform chain blocks the action at the far end`() {
        val workflow = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("t"), NodeTypeId("trigger.sms"), "SMS", 0f, 0f),
                WorkflowNode(
                    NodeId("n"), NodeTypeId("action.notify"), "Notify", 0f, 100f,
                    config = mapOf(ConfigKey("title") to "T"),
                    visibleDataInputs = setOf(PortName("text")),
                ),
                WorkflowNode(
                    NodeId("c"), CONVERT_TYPE_ID, "Convert", 200f, 50f,
                    config = mapOf(CONVERT_TO_KEY to ValueType.NUMBER.name),
                    visibleDataInputs = setOf(CONVERT_IN),
                ),
            ),
            execConnections = listOf(
                ExecConnection("e1", NodeId("t"), PortName("out"), NodeId("n"), PortName("in")),
            ),
            dataConnections = listOf(
                DataConnection("d1", NodeId("t"), PortName("sms"), NodeId("c"), CONVERT_IN),
                DataConnection("d2", NodeId("c"), TRANSFORM_OUT, NodeId("n"), PortName("text")),
            ),
        )
        val validation = GraphValidator(workflow).validate()

        assertTrue(validation.errors.toString(), validation.errors.isNotEmpty())
        assertTrue(
            "the action at the end of the chain is what must not run",
            NodeId("n") in validation.blockedNodes,
        )
    }

    @Test
    fun `an unknown node type is an error that blocks that node`() {
        val workflow = Workflow(
            nodes = listOf(WorkflowNode(NodeId("x"), NodeTypeId("action.from_the_future"), "Future", 0f, 0f)),
        )
        val issue = GraphValidator(workflow).validate().errors.single()

        assertTrue(issue.message, issue.message.contains("unknown node type"))
        assertEquals(setOf(NodeId("x")), issue.nodes)
        assertEquals(setOf(NodeId("x")), issue.blockedNodes)
    }

    @Test
    fun `a workflow with no trigger warns, and an empty one does not`() {
        val noTrigger = Workflow(
            nodes = listOf(WorkflowNode(NodeId("n"), NodeTypeId("action.notify"), "Notify", 0f, 0f)),
        )
        assertTrue(
            GraphValidator(noTrigger).validate().warnings.any { it.message.contains("no trigger") },
        )
        // A workflow the user has only just created is not yet wrong.
        assertTrue(GraphValidator(Workflow()).validate().isEmpty)
    }

    @Test
    fun `a trigger wired to nothing warns against itself`() {
        val workflow = Workflow(
            nodes = listOf(WorkflowNode(NodeId("t"), NodeTypeId("trigger.manual"), "Manual", 0f, 0f)),
        )
        val issue = GraphValidator(workflow).validate().warnings
            .single { it.message.contains("fire and do nothing") }

        assertEquals(setOf(NodeId("t")), issue.nodes)
    }

    private fun unreadValue() = Workflow(
        nodes = listOf(
            WorkflowNode(NodeId("t"), NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
            WorkflowNode(NodeId("n"), NodeTypeId("action.notify"), "Notify", 0f, 100f),
            WorkflowNode(NodeId("v"), NodeTypeId("value.battery"), "Battery", 200f, 0f),
        ),
        execConnections = listOf(
            ExecConnection("e1", NodeId("t"), PortName("out"), NodeId("n"), PortName("in")),
        ),
    )

    private fun starvedTransform() = Workflow(
        nodes = listOf(
            WorkflowNode(NodeId("t"), NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
            WorkflowNode(
                NodeId("n"), NodeTypeId("action.notify"), "Notify", 0f, 100f,
                config = mapOf(ConfigKey("title") to "T"),
                visibleDataInputs = setOf(PortName("text")),
            ),
            WorkflowNode(
                NodeId("c"), CONVERT_TYPE_ID, "Convert", 200f, 50f,
                config = mapOf(CONVERT_TO_KEY to ValueType.TEXT.name),
            ),
        ),
        execConnections = listOf(
            ExecConnection("e1", NodeId("t"), PortName("out"), NodeId("n"), PortName("in")),
        ),
        dataConnections = listOf(
            DataConnection("d1", NodeId("c"), TRANSFORM_OUT, NodeId("n"), PortName("text")),
        ),
    )
}
